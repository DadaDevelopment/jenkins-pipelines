import groovy.text.SimpleTemplateEngine

def call(Map config = [:], Closure body) {
    def agentName = "kubeagent-${env.JOB_BASE_NAME}-${env.BUILD_NUMBER}-${UUID.randomUUID().toString().take(6)}"

    config.sharedCacheClaim = ((config.get('sharedCacheClaim', 'jenkins-build-cache') ?: 'jenkins-build-cache') as String).trim()

    String yaml
    boolean legacy = config.containsKey('yamlTemplate')
    if (legacy) {
        // Legacy path: raw template + vars map. podLabel is derived below and fed
        // back in so callers that reference ${podLabel} still resolve.
        yaml = agentName
    } else {
        yaml = _buildYaml(agentName, config)
    }

    def profile = (config.get('builderProfile', 'job') as String).toLowerCase().replaceAll('[^a-z0-9]', '')
    def podLabel = _stablePodLabel(profile, legacy ? (config.yamlTemplate as String) : yaml, agentName, config)

    if (legacy) {
        def vars = [agentName: agentName, podLabel: podLabel]
        vars.putAll((config.get('yamlVars', [:]) ?: [:]) as Map)
        yaml = new SimpleTemplateEngine().createTemplate(config.yamlTemplate as String).make(vars).toString()
    }

    podTemplate(
            cloud: (config.get('cloud', 'self-managed') ?: 'self-managed') as String,
            label: podLabel,
            namespace: (config.get('namespace', 'devops-tools') ?: 'devops-tools') as String,
            serviceAccount: (config.get('serviceAccount', 'jenkins-admin') ?: 'jenkins-admin') as String,
            // Warm-reuse: idle agents of THIS shape stay online idleMinutes so the
            // next same-shape build reuses the pod (warm dind graph + local caches)
            // instead of cold-starting. podRetention onFailure keeps failed pods for
            // debug; never() would delete on completion and defeat reuse.
            idleMinutes: (config.get('idleMinutes', 20) as Integer),
            podRetention: onFailure(),
            yaml: yaml
    ) {
        // The in-cluster JNLP4 channel (agent -> jenkins svc:50000 over the Cilium
        // service VIP) drops mid-build when the Beget-MANAGED control plane / apiserver
        // flaps (observed: ClosedChannelException / "removed or offline" on builds that
        // happen to run during a blip; a rerun on the SAME config passes). The flap is
        // not ours to fix (cilium/apiserver are managed, not introspectable). So make the
        // build SURVIVE it: retry ONLY on agent/channel loss -> a fresh pod is provisioned
        // and the body re-runs. Real failures (compile/test/push) do NOT match agent() and
        // are not retried, so a genuinely broken build still fails fast.
        retry(count: 3, conditions: [agent()]) {
            node(podLabel) {
                if (config.get('cleanWs', true)) cleanWs()
                body()
            }
        }
    }
}

// ── Stable pod label (warm-reuse) ──────────────────────────────────────────────
// The k8s plugin reuses an idle agent only when the next build asks for the same
// template label. A per-build label => every build cold-starts. Derive the label
// from the pod SHAPE (rendered yaml with the unique agent name neutralised, plus
// the podTemplate-level knobs that are not in the yaml) so identical shapes share
// one label and reuse warm pods, while any shape change forces a distinct label
// (never reuse a differently-toolchained pod).
String _stablePodLabel(String profile, String shapeSource, String agentName, Map config) {
    def shape = (shapeSource ?: '').replace(agentName ?: '', 'AGENT')
    shape += "|cloud=${config.get('cloud', 'self-managed')}" +
             "|ns=${config.get('namespace', 'devops-tools')}" +
             "|sa=${config.get('serviceAccount', 'jenkins-admin')}" +
             "|vars=${config.get('yamlVars', [:])}"
    def md = java.security.MessageDigest.getInstance('SHA-256')
    def hash = md.digest(shape.getBytes('UTF-8')).encodeHex().toString().take(10)
    return "build-${profile}-${hash}".replaceAll('[^A-Za-z0-9-]', '-').toLowerCase()
}

// ── YAML builder ───────────────────────────────────────────────────────────────

String _buildYaml(String agentName, Map cfg) {
    def profile    = (cfg.get('builderProfile', 'node') as String).toLowerCase()
    def builderImg = cfg.get('builderImage', '') as String
    def withDocker = cfg.get('withDocker', false) as boolean
    // Default to docker:24, the ONLY in-repo-proven-good dind: docker:29-dind's embedded
    // BuildKit "session" healthcheck fatally kills dockerd on heavy builds (dind exits 0,
    // agent vanishes). spring/python already pin 24 explicitly; js/go relied on this default
    // and were the last 29 holdouts. Override per-pipeline only for a deliberate bump.
    def dockerCli  = cfg.get('dockerCliImage', 'docker:24-cli') as String
    def dockerDind = cfg.get('dockerDindImage', 'docker:24-dind') as String
    def storage = _storageProfile(profile, withDocker)

    if (!builderImg) error('kubePodTemplate: builderImage is required')

    def cacheVols   = _cacheVolumes(profile, cfg)
    def dockerVols  = withDocker ? "\n${_dockerVolumes(storage.dockerGraphStorageSizeLimit)}" : ''
    def builderEnv  = _builderEnv(profile)
    def builderMnts = _builderMounts(profile, cfg)
    def dockerCtrs  = withDocker ? "\n\n${_dockerContainers(dockerCli, dockerDind, storage)}" : ''

    """\
apiVersion: v1
kind: Pod
metadata:
  name: ${agentName}
  annotations:
    argocd.argoproj.io/tracking-id: jenkins-beget:/Pod:devops-tools/${agentName}
  labels:
    app.kubernetes.io/name: jenkins-agent
    app.kubernetes.io/part-of: jenkins-common
    app.kubernetes.io/managed-by: jenkins
spec:
  priorityClassName: critical
  # Resilience against cluster CoreDNS flaps (single-replica, overloaded):
  # ndots:1 stops search-domain expansion storms for external names like
  # nexus.dada-tuda.ru (otherwise 4+ queries x A/AAAA per lookup); attempts/timeout
  # let a single getaddrinfo() ride out a brief CoreDNS blip instead of EAI_AGAIN.
  dnsConfig:
    options:
      - name: ndots
        value: "1"
      - name: attempts
        value: "3"
      - name: timeout
        value: "2"
  securityContext:
    fsGroup: 1000
  volumes:
    - name: workspace-volume
      emptyDir:
        sizeLimit: 8Gi
${cacheVols}${dockerVols}
    - name: tools-volume
      emptyDir: {}
  containers:
    - name: jnlp
      # Pinned by digest (was floating :latest). This is the exact image that handshakes
      # cleanly against the 2.559-jdk21 controller; a floating tag silently changing the
      # remoting version mid-incident is one variable too many. Bump deliberately.
      image: jenkins/inbound-agent@sha256:65b16b388a32be41d95134a5be7ce58e20fbc4d862e12f021ae64bf83bd5fe7d
      tty: true
      workingDir: /home/jenkins/agent
      resources:
        requests:
          cpu: "50m"
          memory: "128Mi"
          ephemeral-storage: "${storage.jnlpEphemeralRequest}"
        limits:
          cpu: "300m"
          memory: "256Mi"
          ephemeral-storage: "${storage.jnlpEphemeralLimit}"
      volumeMounts:
        - name: workspace-volume
          mountPath: /home/jenkins/agent
        - name: tools-volume
          mountPath: /tools

    - name: builder
      image: ${builderImg}
      command: ['cat']
      tty: true
      workingDir: /home/jenkins/agent
      env:
        - name: HOME
          value: /tmp
${builderEnv}
      resources:
        requests:
          cpu: "250m"
          # request is the scheduler floor (keeps scheduling possible on the
          # mem-request-saturated cluster); the limit is the real ceiling. uv sync
          # --all-extras installing 200+ packages (scipy/numpy/matplotlib/botocore)
          # peaks past 1536Mi and OOMKilled the builder (exit 137) at the link step —
          # node MemoryPressure stays False since it's a per-container cgroup OOM, not
          # node-level. Node has actual headroom (~50-75% used), so burst to 3Gi.
          memory: "512Mi"
          ephemeral-storage: "${storage.builderEphemeralRequest}"
        limits:
          cpu: "1500m"
          memory: "3072Mi"
          ephemeral-storage: "${storage.builderEphemeralLimit}"
      volumeMounts:
        - name: workspace-volume
          mountPath: /home/jenkins/agent
${builderMnts}
        - name: tools-volume
          mountPath: /tools${dockerCtrs}
"""
}

// ── Profile-specific volumes ───────────────────────────────────────────────────

String _cacheVolumes(String profile, Map cfg = [:]) {
    def sharedClaim = ((cfg.get('sharedCacheClaim', '') ?: '') as String).trim()
    if (sharedClaim && profile in ['maven', 'node', 'python', 'go']) {
        return """\
    - name: build-cache
      persistentVolumeClaim:
        claimName: ${sharedClaim}"""
    }
    switch (profile) {
        case 'maven':
            // Opt-in persistent .m2: with `mavenCacheClaim` set, mount a shared
            // (RWX) PVC instead of a per-build emptyDir, so deps survive across
            // builds (cold ~30m download -> warm minutes). Default stays emptyDir
            // so builds that don't opt in are unaffected.
            def mavenClaim = ((cfg.get('mavenCacheClaim', '') ?: '') as String).trim()
            if (mavenClaim) {
                return """\
    - name: m2-cache
      persistentVolumeClaim:
        claimName: ${mavenClaim}"""
            }
            return '''\
    - name: m2-cache
      emptyDir:
        sizeLimit: 2Gi'''
        case 'node':
            return '''\
    - name: npm-cache
      emptyDir:
        sizeLimit: 1Gi
    - name: yarn-cache
      emptyDir:
        sizeLimit: 1Gi
    - name: pnpm-store
      emptyDir:
        sizeLimit: 1Gi'''
        case 'python':
            return '''\
    - name: pip-cache
      emptyDir:
        sizeLimit: 1Gi
    - name: uv-cache
      emptyDir:
        sizeLimit: 1Gi'''
        case 'go':
            def goClaim = ((cfg.get('goCacheClaim', '') ?: '') as String).trim()
            if (goClaim) {
                return """\
    - name: go-mod-cache
      persistentVolumeClaim:
        claimName: ${goClaim}
    - name: go-build-cache
      persistentVolumeClaim:
        claimName: ${goClaim}"""
            }
            return '''\
    - name: go-mod-cache
      emptyDir:
        sizeLimit: 2Gi
    - name: go-build-cache
      emptyDir:
        sizeLimit: 2Gi'''
        default:
            return ''
    }
}

String _dockerVolumes(String dockerGraphStorageSizeLimit) {
    '''\
    - name: docker-graph-storage
      emptyDir:
        sizeLimit: ''' + dockerGraphStorageSizeLimit + '''
    - name: docker-certs
      emptyDir: {}'''
}

// ── Profile-specific builder env vars ─────────────────────────────────────────

String _builderEnv(String profile) {
    switch (profile) {
        case 'maven':
            // DOCKER_HOST points spring-boot:build-image (run in THIS builder
            // container) at the dind sidecar; without it the plugin probes the
            // non-existent /var/run/docker.sock and fails. Harmless when the build
            // does no image step. TLS disabled to match the dind (plain tcp:2375).
            return '''\
        - name: MAVEN_OPTS
          value: -Dmaven.repo.local=/root/.m2/repository/
        - name: DOCKER_HOST
          value: tcp://localhost:2375
        - name: DOCKER_TLS_CERTDIR
          value: ""
        - name: PATH
          value: /usr/share/maven/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin'''
        case 'node':
            return '''\
        - name: NPM_CONFIG_CACHE
          value: /tmp/.cache/npm
        - name: YARN_CACHE_FOLDER
          value: /tmp/.cache/yarn
        - name: PNPM_HOME
          value: /tmp/.local/share/pnpm
        - name: PATH
          value: /tmp/.local/share/pnpm:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin'''
        case 'python':
            return '''\
        - name: PIP_CACHE_DIR
          value: /tmp/.cache/pip
        - name: UV_CACHE_DIR
          value: /tmp/.cache/uv
        - name: XDG_CACHE_HOME
          value: /tmp/.cache
        - name: PATH
          value: /home/jenkins/.local/bin:/usr/local/bin:/usr/bin:/bin'''
        case 'go':
            // GOPATH/GOMODCACHE/GOCACHE live under /tmp (HOME) so the non-root
            // builder can write them; both caches are emptyDir-mounted. GOFLAGS
            // keeps module mode explicit. golang image ships go at /usr/local/go.
            return '''\
        - name: GOPATH
          value: /tmp/go
        - name: GOMODCACHE
          value: /tmp/go/pkg/mod
        - name: GOCACHE
          value: /tmp/.cache/go-build
        - name: GOFLAGS
          value: -mod=mod
        - name: PATH
          value: /tmp/go/bin:/usr/local/go/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin'''
        default:
            return ''
    }
}

// ── Profile-specific builder volume mounts ────────────────────────────────────

String _builderMounts(String profile, Map cfg = [:]) {
    def sharedClaim = ((cfg.get('sharedCacheClaim', '') ?: '') as String).trim()
    if (sharedClaim) {
        switch (profile) {
            case 'maven':
                return '''\
        - name: build-cache
          mountPath: /root/.m2/repository
          subPath: m2'''
            case 'node':
                return '''\
        - name: build-cache
          mountPath: /tmp/.cache/npm
          subPath: npm
        - name: build-cache
          mountPath: /tmp/.cache/yarn
          subPath: yarn
        - name: build-cache
          mountPath: /tmp/.local/share/pnpm
          subPath: pnpm'''
            case 'python':
                return '''\
        - name: build-cache
          mountPath: /tmp/.cache/pip
          subPath: pip
        - name: build-cache
          mountPath: /tmp/.cache/uv
          subPath: uv'''
            case 'go':
                return '''\
        - name: build-cache
          mountPath: /tmp/go/pkg/mod
          subPath: go/mod
        - name: build-cache
          mountPath: /tmp/.cache/go-build
          subPath: go/build'''
            default:
                return ''
        }
    }
    switch (profile) {
        case 'maven':
            return '''\
        - name: m2-cache
          mountPath: /root/.m2/repository'''
        case 'node':
            return '''\
        - name: npm-cache
          mountPath: /tmp/.cache/npm
        - name: yarn-cache
          mountPath: /tmp/.cache/yarn
        - name: pnpm-store
          mountPath: /tmp/.local/share/pnpm'''
        case 'python':
            return '''\
        - name: pip-cache
          mountPath: /tmp/.cache/pip
        - name: uv-cache
          mountPath: /tmp/.cache/uv'''
        case 'go':
            def goClaim = ((cfg.get('goCacheClaim', '') ?: '') as String).trim()
            if (goClaim) {
                return '''\
        - name: go-mod-cache
          mountPath: /tmp/go/pkg/mod
          subPath: mod
        - name: go-build-cache
          mountPath: /tmp/.cache/go-build
          subPath: build'''
            }
            return '''\
        - name: go-mod-cache
          mountPath: /tmp/go/pkg/mod
        - name: go-build-cache
          mountPath: /tmp/.cache/go-build'''
        default:
            return ''
    }
}

// ── Docker sidecar containers (shared between js and python) ──────────────────

String _dockerContainers(String dockerCli, String dockerDind, Map storage) {
    """\
    - name: docker
      image: ${dockerCli}
      command: ['sh', '-c', 'cat']
      tty: true
      workingDir: /home/jenkins/agent
      env:
        - name: HOME
          value: /tmp
        - name: DOCKER_HOST
          value: tcp://localhost:2375
        - name: DOCKER_TLS_CERTDIR
          value: ""
      resources:
        requests:
          cpu: "50m"
          memory: "64Mi"
          ephemeral-storage: "${storage.dockerEphemeralRequest}"
        limits:
          cpu: "250m"
          memory: "128Mi"
          ephemeral-storage: "${storage.dockerEphemeralLimit}"
      volumeMounts:
        - name: workspace-volume
          mountPath: /home/jenkins/agent
        - name: tools-volume
          mountPath: /tools

    - name: dind
      image: ${dockerDind}
      securityContext:
        privileged: true
      env:
        - name: DOCKER_TLS_CERTDIR
          value: ""
      args:
        - --host=tcp://0.0.0.0:2375
        - --host=unix:///var/run/docker.sock
      resources:
        requests:
          cpu: "500m"
          # Memory request is the kubelet EVICTION floor, not just a scheduler
          # reservation: pods using MORE than their request are evicted first
          # under transient node pressure. During docker build, dind burns
          # ~800Mi-1Gi (apt/layer extraction). At a 512Mi request it was the top
          # eviction victim mid-build -> agents vanished ("seems to be removed or
          # offline", graceful SIGTERM, dind exit 0). Nodes are now 12Gi @ ~57%
          # (the 96-99% over-commit that drove the 512Mi cut is gone), so restore
          # 1Gi to cover real build usage and keep the agent eviction-resistant.
          memory: "1Gi"
          ephemeral-storage: "${storage.dindEphemeralRequest}"
        limits:
          cpu: "2000m"
          memory: "4Gi"
          ephemeral-storage: "${storage.dindEphemeralLimit}"
      volumeMounts:
        - name: docker-graph-storage
          mountPath: /var/lib/docker
        - name: docker-certs
          mountPath: /certs
        - name: workspace-volume
          mountPath: /home/jenkins/agent"""
}

Map _storageProfile(String profile, boolean withDocker) {
    switch (profile) {
        case 'maven':
            return [
                    jnlpEphemeralRequest      : '256Mi',
                    jnlpEphemeralLimit        : '512Mi',
                    builderEphemeralRequest   : '2Gi',
                    builderEphemeralLimit     : '4Gi',
                    dockerEphemeralRequest    : withDocker ? '512Mi' : '0Mi',
                    dockerEphemeralLimit      : withDocker ? '1Gi' : '0Mi',
                    dindEphemeralRequest      : withDocker ? '2Gi' : '0Mi',
                    dindEphemeralLimit        : withDocker ? '4Gi' : '0Mi',
                    dockerGraphStorageSizeLimit: withDocker ? '8Gi' : '0Gi',
            ]
        case 'python':
            return [
                    jnlpEphemeralRequest      : '256Mi',
                    jnlpEphemeralLimit        : '512Mi',
                    builderEphemeralRequest   : '4Gi',
                    builderEphemeralLimit     : '8Gi',
                    dockerEphemeralRequest    : withDocker ? '512Mi' : '0Mi',
                    dockerEphemeralLimit      : withDocker ? '1Gi' : '0Mi',
                    dindEphemeralRequest      : withDocker ? '2Gi' : '0Mi',
                    dindEphemeralLimit        : withDocker ? '4Gi' : '0Mi',
                    dockerGraphStorageSizeLimit: withDocker ? '8Gi' : '0Gi',
            ]
        case 'node':
            return [
                    jnlpEphemeralRequest      : '256Mi',
                    jnlpEphemeralLimit        : '512Mi',
                    builderEphemeralRequest   : '4Gi',
                    builderEphemeralLimit     : '8Gi',
                    dockerEphemeralRequest    : withDocker ? '512Mi' : '0Mi',
                    dockerEphemeralLimit      : withDocker ? '1Gi' : '0Mi',
                    dindEphemeralRequest      : withDocker ? '2Gi' : '0Mi',
                    dindEphemeralLimit        : withDocker ? '4Gi' : '0Mi',
                    dockerGraphStorageSizeLimit: withDocker ? '8Gi' : '0Gi',
            ]
        case 'go':
            return [
                    jnlpEphemeralRequest      : '256Mi',
                    jnlpEphemeralLimit        : '512Mi',
                    builderEphemeralRequest   : '3Gi',
                    builderEphemeralLimit     : '6Gi',
                    dockerEphemeralRequest    : withDocker ? '512Mi' : '0Mi',
                    dockerEphemeralLimit      : withDocker ? '1Gi' : '0Mi',
                    dindEphemeralRequest      : withDocker ? '2Gi' : '0Mi',
                    dindEphemeralLimit        : withDocker ? '4Gi' : '0Mi',
                    dockerGraphStorageSizeLimit: withDocker ? '8Gi' : '0Gi',
            ]
        default:
            return [
                    jnlpEphemeralRequest      : '256Mi',
                    jnlpEphemeralLimit        : '512Mi',
                    builderEphemeralRequest   : '2Gi',
                    builderEphemeralLimit     : '4Gi',
                    dockerEphemeralRequest    : withDocker ? '512Mi' : '0Mi',
                    dockerEphemeralLimit      : withDocker ? '1Gi' : '0Mi',
                    dindEphemeralRequest      : withDocker ? '1Gi' : '0Mi',
                    dindEphemeralLimit        : withDocker ? '2Gi' : '0Mi',
                    dockerGraphStorageSizeLimit: withDocker ? '4Gi' : '0Gi',
            ]
    }
}

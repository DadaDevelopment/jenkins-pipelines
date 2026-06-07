import groovy.text.SimpleTemplateEngine

def call(Map config = [:], Closure body) {
    def podLabel = "kubeagent-${env.JOB_BASE_NAME ?: 'job'}-${env.BUILD_NUMBER ?: 'manual'}"
            .replaceAll('[^A-Za-z0-9-]', '-').toLowerCase()
    def agentName = "kubeagent-${env.JOB_BASE_NAME}-${env.BUILD_NUMBER}-${UUID.randomUUID().toString().take(6)}"

    String yaml
    if (config.containsKey('yamlTemplate')) {
        // Legacy path: raw template + vars map
        def vars = [agentName: agentName, podLabel: podLabel]
        vars.putAll((config.get('yamlVars', [:]) ?: [:]) as Map)
        yaml = new SimpleTemplateEngine().createTemplate(config.yamlTemplate as String).make(vars).toString()
    } else {
        yaml = _buildYaml(agentName, config)
    }

    podTemplate(
            cloud: (config.get('cloud', 'self-managed') ?: 'self-managed') as String,
            label: podLabel,
            namespace: (config.get('namespace', 'devops-tools') ?: 'devops-tools') as String,
            serviceAccount: (config.get('serviceAccount', 'jenkins-admin') ?: 'jenkins-admin') as String,
            yaml: yaml
    ) {
        node(podLabel) {
            if (config.get('cleanWs', true)) cleanWs()
            body()
        }
    }
}

// ── YAML builder ───────────────────────────────────────────────────────────────

String _buildYaml(String agentName, Map cfg) {
    def profile    = (cfg.get('builderProfile', 'node') as String).toLowerCase()
    def builderImg = cfg.get('builderImage', '') as String
    def withDocker = cfg.get('withDocker', false) as boolean
    def dockerCli  = cfg.get('dockerCliImage', 'docker:29-cli') as String
    def dockerDind = cfg.get('dockerDindImage', 'docker:29-dind') as String
    def storage = _storageProfile(profile, withDocker)

    if (!builderImg) error('kubePodTemplate: builderImage is required')

    def cacheVols   = _cacheVolumes(profile)
    def dockerVols  = withDocker ? "\n${_dockerVolumes(storage.dockerGraphStorageSizeLimit)}" : ''
    def builderEnv  = _builderEnv(profile)
    def builderMnts = _builderMounts(profile)
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
      image: jenkins/inbound-agent:latest
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
          memory: "512Mi"
          ephemeral-storage: "${storage.builderEphemeralRequest}"
        limits:
          cpu: "1500m"
          memory: "1536Mi"
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

String _cacheVolumes(String profile) {
    switch (profile) {
        case 'maven':
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
        default:
            return ''
    }
}

// ── Profile-specific builder volume mounts ────────────────────────────────────

String _builderMounts(String profile) {
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

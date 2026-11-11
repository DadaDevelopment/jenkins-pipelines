// dadaBuildPipeline — centralized build job for DADA Cloud user apps.
//
// User repos carry NO Jenkinsfile. The build-agent control plane
// (dada-cloud/build-agent) triggers this job with parameters over
// buildWithParameters, streams the console back, and parses the `==> image:`
// marker. This is the single source of truth for that job — the Jenkins job
// config is just:
//
//     @Library('dada-tuda-jenkins-pipelines@develop') _
//     dadaBuildPipeline()
//
// When the cloned repo ships a Dockerfile it wins. Otherwise this templates one
// from the framework the build-agent detected (install/build/start/output/port
// are passed as parameters), so framework apps (Next.js, Vite, FastAPI, Go, …)
// build without the user authoring a Dockerfile.

import ru.dada.build.GoSite
import ru.dada.build.PythonLaunch
import ru.dada.build.RootDir
import ru.dada.build.StaticSite

def call(Map config = [:]) {
    pipeline {
        agent {
            kubernetes {
                cloud 'self-managed'
                defaultContainer 'jnlp'
                label 'dada-build-agent'
                showRawYaml false
                yaml """
apiVersion: v1
kind: Pod
spec:
  # Keep console-triggered builds ahead of ordinary workloads without granting
  # them the right to evict anything.  The PriorityClass has value 100000 and
  # preemptionPolicy: Never; it is deliberately not `critical`.
  priorityClassName: ci-agent
  containers:
  # Same digest kubePodTemplate pins and jenkins-warm-cache keeps on every node.
  # Left to the plugin default (a floating -jdk tag) it missed the node cache and
  # cost a 6.7s pull on #317.
  - name: jnlp
    image: jenkins/inbound-agent@sha256:65b16b388a32be41d95134a5be7ce58e20fbc4d862e12f021ae64bf83bd5fe7d
  - name: docker
    image: docker:24-cli
    command: ['sleep']
    args: ['infinity']
    tty: true
  # buildx remote driver target. kubelet pulls this image, so the node copy that
  # jenkins-warm-cache pins is reused; a buildkit started inside dind pulled it
  # into dind's own store on every build (7s) and waited for dockerd (10s).
  # Loopback only: nothing outside the pod can submit a build.
  - name: buildkitd
    image: moby/buildkit:buildx-stable-1
    args: ['--addr', 'tcp://127.0.0.1:1234']
    securityContext:
      privileged: true
    volumeMounts:
    - name: buildkit-state
      mountPath: /var/lib/buildkit
  - name: psql
    image: postgres:16-alpine
    command: ['sleep']
    args: ['infinity']
    tty: true
  volumes:
  - name: buildkit-state
    emptyDir:
      sizeLimit: 30Gi
"""
            }
        }
        // No disableConcurrentBuilds(): every build runs in its own pod with its
        // own workspace and buildkitd sidecar, image tags carry BUILD_NUMBER, and
        // same repo+branch races are resolved upstream by the build-agent
        // (BUILD_MAX_CONCURRENT semaphore + supersession). Serializing this job
        // made one slow clone or upload archive block every customer's build.
        options { timeout(time: 20, unit: 'MINUTES') }
        parameters {
            string(name: 'repo',               defaultValue: '',      description: 'authenticated clone URL (sent by build-agent)')
            string(name: 'archive_url',        defaultValue: '',      description: 'presigned GET URL for an uploaded source archive (zip/tar.gz); when set this wins over repo/branch clone')
            string(name: 'branch',             defaultValue: 'main',  description: 'git branch')
            string(name: 'framework',          defaultValue: 'auto',  description: 'auto|web|android (build family)')
            string(name: 'buildType',          defaultValue: 'debug', description: 'reserved (android signing)')
            string(name: 'env',                defaultValue: '',      description: 'environment_id UUID (DB-lookup fallback)')
            string(name: 'project_slug',       defaultValue: '',      description: 'preferred identity from build-agent runner')
            string(name: 'app_name',           defaultValue: '',      description: 'preferred identity from build-agent runner')
            string(name: 'root_dir',           defaultValue: '',      description: 'monorepo subdirectory to build from (build context + Dockerfile); empty = repo root')
            // Build-time framework detection (sent by build-agent). Used to
            // template a Dockerfile when the repo has none. Empty = unknown.
            string(name: 'detected_framework', defaultValue: '',      description: 'specific framework: nextjs|node|vite|python|go|...')
            string(name: 'package_manager',    defaultValue: '',      description: 'npm|pnpm|yarn|bun (node only)')
            string(name: 'install_cmd',        defaultValue: '',      description: 'dependency install command')
            string(name: 'build_cmd',          defaultValue: '',      description: 'build command')
            string(name: 'start_cmd',          defaultValue: '',      description: 'runtime start command')
            string(name: 'output_dir',         defaultValue: '',      description: 'static build output dir (SPA/static)')
            string(name: 'app_port',           defaultValue: '',      description: 'listen port')
            string(name: '__proj',             defaultValue: '',      description: 'SMOKE-TEST ONLY identity override')
            string(name: '__app',              defaultValue: '',      description: 'SMOKE-TEST ONLY identity override')
            // Build provenance for the Jenkins build list: who/why it was started
            // and for whom. Sent by the build-agent; empty values (smoke tests)
            // simply produce a shorter description line.
            string(name: 'build_id',           defaultValue: '',      description: 'cloud-console builds.id UUID (join key for audit_events)')
            string(name: 'trigger',            defaultValue: '',      description: 'push | pr | manual | rollback')
            string(name: 'actor',              defaultValue: '',      description: 'who started it: user email or <provider>-webhook')
            string(name: 'client',             defaultValue: '',      description: 'project owner email (best effort)')
        }
        stages {
            stage('resolve identity') {
                steps {
                    retry(2) {
                    script {
                        if (params.project_slug?.trim() && params.app_name?.trim()) {
                            env.PROJ = params.project_slug.trim(); env.APP = params.app_name.trim(); env.ROOTDIR = buildRootDir(params.root_dir)
                            echo "identity (params): ${env.PROJ}/${env.APP} rootdir=${env.ROOTDIR}"
                        } else if (params.__proj?.trim() && params.__app?.trim()) {
                            env.PROJ = params.__proj.trim(); env.APP = params.__app.trim(); env.ROOTDIR = buildRootDir(params.root_dir)
                            echo "identity (override): ${env.PROJ}/${env.APP} rootdir=${env.ROOTDIR}"
                        } else {
                            def rf = sh(returnStdout: true, script: 'R="$repo"; R="${R#*://}"; R="${R#*@}"; R="${R#*/}"; R="${R%.git}"; printf "%s" "$R"').trim()
                            container('psql') {
                                withCredentials([string(credentialsId: 'cloud-console-db-password', variable: 'PGPASSWORD')]) {
                                    def q = "SELECT p.name, r.app_name, COALESCE(NULLIF(r.root_dir,''),'.') FROM git_repos r JOIN projects p ON p.id=r.project_id WHERE r.environment_id='" + params.env + "' AND r.repo_full_name='" + rf + "' LIMIT 1"
                                    def row = sh(returnStdout: true, script: "psql -h postgresql.databases.svc.cluster.local -U dada -d cloud-console -tAF '|' -c \"" + q + "\"").trim()
                                    if (!row) { error("no git_repos row for env=" + params.env + " repo=" + rf) }
                                    def f = row.split('\\|')
                                    env.PROJ = f[0]; env.APP = f[1]; env.ROOTDIR = buildRootDir(f.size() > 2 ? f[2] : '')
                                }
                            }
                            echo "identity (db): ${env.PROJ}/${env.APP} rootdir=${env.ROOTDIR}"
                        }
                    }
                    script {
                        def parts = ["${env.PROJ}/${env.APP}"]
                        if (params.trigger?.trim()) { parts << params.trigger.trim() }
                        parts << "by ${(params.actor ?: 'unknown').trim()}"
                        if (params.client?.trim() && params.client.trim() != params.actor?.trim()) { parts << "client ${params.client.trim()}" }
                        if (params.build_id?.trim()) { parts << 'build ' + params.build_id.trim().take(8) }
                        currentBuild.description = parts.join(' · ')
                    }
                    }
                }
            }
            stage('clone') {
                // Stage budget below the job-wide 20min backstop: a dead storage
                // endpoint or a stalled GitHub link must fail this stage inside
                // 10 minutes even with the retry, not eat the whole build.
                options { timeout(time: 10, unit: 'MINUTES') }
                steps {
                    retry(2) {
                    script {
                        if (params.archive_url?.trim()) {
                            sh '''
                                set -e
                                rm -rf src src.bin
                                # xtrace prints expanded arguments, and the archive URL
                                # is a presigned credential: keep the download itself
                                # out of the console. Same for the git clone below.
                                { set +x; } 2>/dev/null
                                curl -fsSL --connect-timeout 15 --retry 2 --retry-delay 3 --retry-connrefused --max-time 300 "$archive_url" -o src.bin
                                { set -x; } 2>/dev/null
                                mkdir -p src
                                MAGIC=$(head -c 4 src.bin | od -An -tx1 | tr -d ' \\n')
                                case "$MAGIC" in
                                    504b0304)
                                        if command -v unzip >/dev/null 2>&1; then
                                            unzip -q src.bin -d src/
                                        else
                                            ( cd src && jar -xf ../src.bin )
                                        fi
                                        ;;
                                    1f8b*)
                                        tar -xzf src.bin -C src/
                                        ;;
                                    *)
                                        echo "unsupported archive format (magic bytes: $MAGIC)" >&2
                                        exit 1
                                        ;;
                                esac
                                rm -f src.bin
                                ESCAPED=$(find src/ -path '*..*' | head -1)
                                if [ -n "$ESCAPED" ]; then
                                    echo "zip-slip guard: entry escapes src/: $ESCAPED" >&2
                                    exit 1
                                fi
                                CONTENT_ENTRIES=$(find src -mindepth 1 -maxdepth 1 ! -name '.*' ! -name '__MACOSX')
                                ENTRY_COUNT=$(printf '%s\\n' "$CONTENT_ENTRIES" | grep -c . || true)
                                if [ "$ENTRY_COUNT" = "1" ]; then
                                    ONLY_ENTRY=$CONTENT_ENTRIES
                                    if [ -d "$ONLY_ENTRY" ]; then
                                        TMPDIR=$(mktemp -d)
                                        find "$ONLY_ENTRY" -mindepth 1 -maxdepth 1 -exec mv {} "$TMPDIR"/ \\;
                                        rmdir "$ONLY_ENTRY"
                                        find "$TMPDIR" -mindepth 1 -maxdepth 1 -exec mv {} src/ \\;
                                        rmdir "$TMPDIR"
                                    fi
                                fi
                            '''
                        } else {
                            sh '''
                                set -e
                                rm -rf src
                                { set +x; } 2>/dev/null
                                git -c http.lowSpeedLimit=2048 -c http.lowSpeedTime=60 clone --depth 1 --branch "$branch" "$repo" src
                                { set -x; } 2>/dev/null
                            '''
                        }
                    }
                    }
                }
            }
            stage('build & push') {
                steps {
                    retry(2) {
                    script {
                        env.TAG = (params.branch + '-b' + env.BUILD_NUMBER).replaceAll('[^A-Za-z0-9_.-]', '-')
                        env.IMG = 'nexus.dada-tuda.ru/' + env.PROJ + '/' + env.APP
                        env.CTX = 'src/' + (env.ROOTDIR ?: RootDir.REPO_ROOT)
                        if (env.CTX != 'src/' + RootDir.REPO_ROOT && !fileExists(env.CTX)) {
                            error("root_dir '${env.ROOTDIR}' does not exist in ${params.branch ?: 'the checkout'}")
                        }
                        env.CACHE_REF = env.IMG + ':buildcache-' + (params.branch ?: 'main').replaceAll('[^A-Za-z0-9_.-]', '-')

                        def sourceEntries = sh(returnStdout: true, script: "find src -mindepth 1 2>/dev/null | head -3").trim()
                        if (!sourceEntries) {
                            error("the checkout is empty: branch '${params.branch ?: 'default'}' has no files to build — push code to the branch and rebuild")
                        }

                        def dfName = ['Dockerfile', 'dockerfile'].find { fileExists(env.CTX + '/' + it) }
                        def dfPath = env.CTX + '/' + (dfName ?: 'Dockerfile')
                        env.DOCKERFILE_PATH = dfPath
                        if (dfName) {
                            echo "using repo Dockerfile at ${dfPath}"
                        } else {
                            def fw = (params.detected_framework ?: '').trim()
                            if (!fw) {
                                fw = sniffFrameworkFromCheckout(env.CTX)
                                if (fw) {
                                    echo "framework was empty in the build request — read '${fw}' off the checked-out sources"
                                }
                            }
                            def pySources = PythonLaunch.TEMPLATE_FRAMEWORKS.contains(fw) ? pythonEntrypointSources(env.CTX) : [:]
                            def df = renderDockerfile(fw, [
                                install: (params.install_cmd ?: '').trim(),
                                build  : (params.build_cmd ?: '').trim(),
                                start  : (params.start_cmd ?: '').trim(),
                                output : (params.output_dir ?: '').trim(),
                                port   : parsePort(params.app_port),
                                sources: pySources,
                                goMod  : (fw == 'go' && fileExists(env.CTX + '/go.mod')) ? readFile(env.CTX + '/go.mod') : '',
                            ])
                            if (df == null) {
                                // Diagnose the empty-repo shape before the generic error: a
                                // skeleton (README/.gitignore/docker-compose only) reads as
                                // "forgot to push the code", not as "add a manifest" (CI dada-build
                                // #86: compose said build:. + python -m app.main, repo held 3 files).
                                def probe = sh(returnStdout: true, script: """
                                    cd '${env.CTX}' 2>/dev/null || true
                                    files=\$(find . -type f -not -path "./.git/*" 2>/dev/null | wc -l)
                                    names=\$(find . -type f -not -path "./.git/*" 2>/dev/null | sed "s|.*/||" | sort -u | head -8 | paste -sd ',' -)
                                    compose=\$(ls docker-compose.yml docker-compose.yaml compose.yml 2>/dev/null | head -1)
                                    cmd=\$(grep -m1 "command:" docker-compose.yml docker-compose.yaml compose.yml 2>/dev/null | sed "s/.*command:[[:space:]]*//" | tr -d '"')
                                    echo "\$files|\$names|\$compose|\$cmd"
                                """).trim()
                                def parts = probe.split("\\|", -1) as List
                                def fileCount = parts[0] ?: '0'
                                def fileNames = parts[1] ?: ''
                                def composeFile = parts[2] ?: ''
                                def composeCmd = parts[3] ?: ''
                                def fileCountN = (fileCount as String).isNumber() ? fileCount.toInteger() : 0
                                if (fileCountN <= 4 && composeFile) {
                                    error("repo has no application code yet: only ${fileCountN} file(s) committed (${fileNames}); ${composeFile} expects 'build: .' with a Dockerfile and source in the repo${composeCmd ? ", and runs '${composeCmd}'" : ''} -- push the application code (plus requirements.txt/pyproject.toml) and rebuild")
                                }
                                error("could not digest this source: no Dockerfile at the checkout root and no manifest the platform can build (package.json, go.mod, requirements.txt/pyproject.toml, index.html). Check that branch '${params.branch ?: 'default'}' actually contains the application, or extend dadaBuildPipeline")
                            }
                            writeFile file: dfPath, text: df
                            echo "no Dockerfile in repo — generated for framework='${fw}':\n--- ${dfPath} ---\n${df}-----------------"
                        }
                    }
                    script {
                    container('docker') {
                        def bx = [
                                image: env.IMG + ':' + env.TAG,
                                cacheRef: env.CACHE_REF,
                                dockerfile: env.DOCKERFILE_PATH,
                                context: env.CTX,
                                buildkitAddr: 'tcp://127.0.0.1:1234',
                        ]
                        dockerBuildxPush(bx + [metadataFile: 'image-meta.json', cacheMode: 'none'])
                        sh """
                            set -e
                            DIG=\$(tr -d '[:space:]' < image-meta.json | grep -oE '"containerimage.digest":"sha256:[0-9a-f]{64}"' | grep -oE 'sha256:[0-9a-f]{64}' | head -1)
                            test -n "\${DIG}"
                            echo "==> image: ${env.IMG}@\${DIG}"
                        """
                        dockerBuildxPush(bx + [cacheMode: 'export-only'])
                    }
                    }
                    sleep time: 5, unit: 'SECONDS'
                    echo "build & push complete for ${env.IMG}:${env.TAG}"
                    }
                }
            }
        }
        post {
            failure {
                // The landing promises AI auto-fix on CI failure. Git-based
                // builds report to the AgentSync Hub, which opens a
                // CI-BUGFIX workflow and (for repo builds) proposes a fix PR
                // through the console UI. Archive (zip-upload) builds carry
                // no git identity, so the hub has nothing to patch yet -
                // they are skipped here until the FS-workspace flow ships.
                script {
                    if (!params.archive_url?.trim()) {
                        def summary = currentBuild.description ?: 'dada-build failed'
                        def payload = [
                            repo_url       : (params.repo ?: '').replaceFirst('https://x-access-token:[^@]+@', 'https://'),
                            branch         : (params.branch ?: 'main'),
                            commit_sha     : (params.commit_sha ?: '').take(40) ?: 'unknown',
                            job_name       : 'dada-build',
                            build_number   : env.BUILD_NUMBER,
                            build_url      : env.BUILD_URL,
                            failed_stage   : (env.FAILED_STAGE ?: 'build & push'),
                            failure_summary: summary,
                            trace_id       : "dada-build#${env.BUILD_NUMBER}",
                            idempotency_key: "dada-build:${env.BUILD_NUMBER}",
                            project_slug   : (env.PROJ ?: params.project_slug ?: ''),
                            app_name       : (env.APP ?: params.app_name ?: ''),
                            build_id       : (params.build_id ?: ''),
                        ]
                        writeFile file: '.ci-failed-payload.json', text: groovy.json.JsonOutput.toJson(payload)
                        withCredentials([string(credentialsId: 'agentsync-ci-webhook-token', variable: 'AGENTSYNC_TOKEN')]) {
                            sh '''
                                set +x
                                curl -fsS --max-time 15 -X POST \
                                  http://agent-sync-hub-backend.argocd-prod.svc.cluster.local:8080/v1/agentsync/events/ci-failed \
                                  -H "Authorization: Bearer $AGENTSYNC_TOKEN" \
                                  -H "Content-Type: application/json" \
                                  --data-binary @.ci-failed-payload.json \
                                || echo "agentsync ci-failed webhook failed (non-blocking)"
                            '''
                        }
                    } else {
                        echo "archive build: no git identity - agentsync ci-failed skipped (FS-workspace flow pending)"
                    }
                }
            }
        }
    }
}

/**
 * buildRootDir turns the root_dir parameter (or the git_repos column on the
 * db fallback) into the directory the build runs from: '.' for the repo root,
 * the cleaned subdirectory otherwise. An unsafe value fails the build here,
 * before it can reach a shell path.
 */
def buildRootDir(String raw) {
    String dir = RootDir.normalize(raw)
    if (dir == null) {
        error("root_dir '${raw}' is not a relative path inside the repository (no '..', no leading '/', only letters, digits, '.', '_', '-', '/')")
    }
    return dir
}

// parsePort returns the numeric port or 0 when the parameter is empty/garbage.
/**
 * sniffFrameworkFromCheckout is the last line of defence when the build request
 * carries no framework: read the answer off the sources already on disk. Every
 * earlier detector works over an API (GitHub contents, the upload archive
 * listing) and can come back empty for reasons that have nothing to do with the
 * repo — a listing call that failed, an installation token that could not read a
 * private tree, a shape nobody taught it yet. The clone in the workspace has no
 * such failure mode: it is the code we are about to build.
 *
 * Live case 2026-08-13: keksmd/family-tree connected from GitHub and built with
 * framework '' twice, while the very same sources sitting in src/ carried a
 * python entrypoint one directory down.
 *
 * Looks two levels deep, matching the wrapper-folder shape both other detectors
 * already handle. Deliberately narrower than they are: only shapes whose
 * template works with no further input (node, go, python). 'static' is left out
 * on purpose — that template runs an npm build, so guessing it for a lone
 * index.html would trade a clear "no framework" error for a confusing build
 * failure.
 */
def sniffFrameworkFromCheckout(String ctx) {
    return sh(returnStdout: true, script: """
        cd '${ctx}' 2>/dev/null || exit 0
        if [ -f package.json ]; then echo node; exit 0; fi
        if [ -f go.mod ]; then echo go; exit 0; fi
        if find . -maxdepth 2 \\( -name 'requirements.txt' -o -name 'pyproject.toml' -o -name 'setup.py' -o -name '*.py' \\) 2>/dev/null | head -1 | grep -q . ; then
            echo python
            exit 0
        fi
        if [ -f index.html ] || [ -f index.htm ]; then echo static; exit 0; fi
    """).trim()
}

/**
 * pythonEntrypointSources reads the python files that could carry an ASGI
 * application, so PythonLaunch can turn the entrypoint into a dotted module
 * instead of a guessed file name. Root-level names are read too, because their
 * mere presence is what keeps a repo that works today on the legacy launch
 * path. Nothing beyond those candidate names is read: a full workspace read
 * would be unbounded.
 */
def pythonEntrypointSources(String ctx) {
    def rootNames = ru.dada.build.PythonLaunch.ROOT_ENTRY_NAMES.join(' ')
    def nestedNames = ru.dada.build.PythonLaunch.NESTED_ENTRY_NAMES.join(' ')
    def listing = sh(returnStdout: true, script: """
        cd '${ctx}' 2>/dev/null || exit 0
        for f in ${rootNames}; do
            [ -f "\$f" ] && echo "\$f"
        done
        for d in */; do
            for f in ${nestedNames}; do
                [ -f "\$d\$f" ] && echo "\$d\$f"
            done
        done
        exit 0
    """).trim()
    def sources = [:]
    if (!listing) {
        return sources
    }
    listing.split('\n').each { line ->
        def rel = line.trim()
        if (rel) {
            sources[rel] = readFile(file: ctx + '/' + rel)
        }
    }
    return sources
}

def parsePort(String raw) {
    def s = (raw ?: '').trim()
    return s ==~ /\d+/ ? (s as int) : 0
}

// renderDockerfile templates a Dockerfile from the detected framework + the
// install/build/start/output commands the build-agent passed. Returns null when
// the framework has no template (caller errors out). The cloned repo wins if it
// ships its own Dockerfile — this is only reached when it does not.
def renderDockerfile(String fw, Map d) {
    String install = d.install
    String build   = d.build
    String start   = d.start
    String output  = d.output ?: 'dist'
    int port       = (d.port ?: 0)

    switch (fw) {
        // Node runtime: real server (Next.js, Nuxt, SvelteKit, Express, …).
        // Single stage so node_modules + build output survive to runtime.
        case ['nextjs', 'nuxt', 'sveltekit', 'remix', 'express', 'nestjs', 'fastify', 'node']:
            int p = port ?: 3000
            def sb = new StringBuilder()
            sb << "FROM node:20-bookworm\n"
            sb << "WORKDIR /app\n"
            // corepack ships the pnpm/yarn shims with Node; the packageManager
            // field / lockfile selects the actual PM. Harmless for npm.
            sb << "RUN corepack enable\n"
            sb << "COPY . .\n"
            sb << "RUN ${install ?: 'npm install'}\n"
            if (build) { sb << "RUN ${build}\n" }
            sb << "ENV NODE_ENV=production\n"
            sb << "ENV PORT=${p}\n"
            sb << "EXPOSE ${p}\n"
            if (fw == 'nextjs') {
                sb << "RUN npm i -g serve@14\n"
                sb << "CMD [\"sh\",\"-c\",\"if [ -d out ]; then exec serve -s out -l tcp://0.0.0.0:${p}; else exec ${start ?: 'npm start'}; fi\"]\n"
            } else {
                sb << "CMD ${start ?: 'npm start'}\n"
            }
            return sb.toString()

        // Static / SPA: build when the repo has a package.json, then serve the
        // result with nginx. A repo of plain HTML has nothing to install and is
        // served as-is — see ru.dada.build.StaticSite.
        case StaticSite.TEMPLATE_FRAMEWORKS:
            return StaticSite.dockerfile(port, install, build, output)

        case ['fastapi', 'django', 'flask', 'python']:
            int p = port ?: 8000
            String pyInstall = install ?: 'if [ -f requirements.txt ]; then uv pip install --system --no-cache -r requirements.txt; elif [ -f pyproject.toml ]; then uv pip install --system --no-cache .; elif [ -f setup.py ]; then uv pip install --system --no-cache -e .; else r=$(ls */requirements.txt 2>/dev/null | head -1); if [ -n "$r" ]; then uv pip install --system --no-cache -r "$r"; else echo "no python manifest - skipping install"; fi; fi'
            String pyStart = PythonLaunch.startLine(fw, p, (Map<String, String>) (d.sources ?: [:]), start)
            return """FROM python:3.12-slim
WORKDIR /app
COPY --from=ghcr.io/astral-sh/uv:latest /uv /usr/local/bin/uv
COPY . .
RUN ${pyInstall}
ENV PORT=${p}
ENV PYTHONUNBUFFERED=1
EXPOSE ${p}
CMD ["sh","-c","${pyStart.replace('\\', '\\\\').replace('"', '\\"')}"]
"""

        case 'go':
            return GoSite.dockerfile(port, (String) d.goMod)

        default:
            return null
    }
}

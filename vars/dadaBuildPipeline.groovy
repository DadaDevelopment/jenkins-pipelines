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

import ru.dada.build.PythonLaunch
import ru.dada.build.StaticSite

def call(Map config = [:]) {
    pipeline {
        agent {
            kubernetes {
                cloud 'self-managed'
                defaultContainer 'jnlp'
                label 'dada-build-agent'
                yaml """
apiVersion: v1
kind: Pod
spec:
  containers:
  - name: docker
    image: docker:24-cli
    command: ['sleep']
    args: ['infinity']
    tty: true
    env:
    - name: DOCKER_HOST
      value: tcp://localhost:2375
  - name: dind
    image: docker:24-dind
    securityContext:
      privileged: true
    env:
    - name: DOCKER_TLS_CERTDIR
      value: ""
    args: ['--host=tcp://0.0.0.0:2375']
  - name: psql
    image: postgres:16-alpine
    command: ['sleep']
    args: ['infinity']
    tty: true
"""
            }
        }
        options { timeout(time: 20, unit: 'MINUTES'); disableConcurrentBuilds() }
        parameters {
            string(name: 'repo',               defaultValue: '',      description: 'authenticated clone URL (sent by build-agent)')
            string(name: 'archive_url',        defaultValue: '',      description: 'presigned GET URL for an uploaded source archive (zip/tar.gz); when set this wins over repo/branch clone')
            string(name: 'branch',             defaultValue: 'main',  description: 'git branch')
            string(name: 'framework',          defaultValue: 'auto',  description: 'auto|web|android (build family)')
            string(name: 'buildType',          defaultValue: 'debug', description: 'reserved (android signing)')
            string(name: 'env',                defaultValue: '',      description: 'environment_id UUID (DB-lookup fallback)')
            string(name: 'project_slug',       defaultValue: '',      description: 'preferred identity from build-agent runner')
            string(name: 'app_name',           defaultValue: '',      description: 'preferred identity from build-agent runner')
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
        }
        stages {
            stage('resolve identity') {
                steps {
                    script {
                        if (params.project_slug?.trim() && params.app_name?.trim()) {
                            env.PROJ = params.project_slug.trim(); env.APP = params.app_name.trim(); env.ROOTDIR = '.'
                            echo "identity (params): ${env.PROJ}/${env.APP}"
                        } else if (params.__proj?.trim() && params.__app?.trim()) {
                            env.PROJ = params.__proj.trim(); env.APP = params.__app.trim(); env.ROOTDIR = '.'
                            echo "identity (override): ${env.PROJ}/${env.APP}"
                        } else {
                            def rf = sh(returnStdout: true, script: 'R="$repo"; R="${R#*://}"; R="${R#*@}"; R="${R#*/}"; R="${R%.git}"; printf "%s" "$R"').trim()
                            container('psql') {
                                withCredentials([string(credentialsId: 'cloud-console-db-password', variable: 'PGPASSWORD')]) {
                                    def q = "SELECT p.name, r.app_name, COALESCE(NULLIF(r.root_dir,''),'.') FROM git_repos r JOIN projects p ON p.id=r.project_id WHERE r.environment_id='" + params.env + "' AND r.repo_full_name='" + rf + "' LIMIT 1"
                                    def row = sh(returnStdout: true, script: "psql -h postgresql.databases.svc.cluster.local -U dada -d cloud-console -tAF '|' -c \"" + q + "\"").trim()
                                    if (!row) { error("no git_repos row for env=" + params.env + " repo=" + rf) }
                                    def f = row.split('\\|')
                                    env.PROJ = f[0]; env.APP = f[1]; env.ROOTDIR = (f.size() > 2 ? f[2] : '.')
                                }
                            }
                            echo "identity (db): ${env.PROJ}/${env.APP} rootdir=${env.ROOTDIR}"
                        }
                    }
                }
            }
            stage('clone') {
                steps {
                    script {
                        if (params.archive_url?.trim()) {
                            sh '''
                                set -e
                                rm -rf src src.bin
                                curl -fsSL "$archive_url" -o src.bin
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
                            sh 'set -e; rm -rf src; git clone --depth 1 --branch "$branch" "$repo" src'
                        }
                    }
                }
            }
            stage('build & push') {
                steps {
                    script {
                        env.TAG = (params.branch + '-b' + env.BUILD_NUMBER).replaceAll('[^A-Za-z0-9_.-]', '-')
                        env.IMG = 'nexus.dada-tuda.ru/' + env.PROJ + '/' + env.APP
                        env.CTX = 'src/' + (env.ROOTDIR ?: '.')
                        env.CACHE_REF = env.IMG + ':buildcache-' + (params.branch ?: 'main').replaceAll('[^A-Za-z0-9_.-]', '-')

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
                            ])
                            if (df == null) {
                                error("framework '${fw}' has no template and repo ships no Dockerfile — add a Dockerfile to the repo or extend dadaBuildPipeline")
                            }
                            writeFile file: dfPath, text: df
                            echo "no Dockerfile in repo — generated for framework='${fw}':\n--- ${dfPath} ---\n${df}-----------------"
                        }
                    }
                    container('docker') {
                        sh 'for i in $(seq 1 30); do docker info >/dev/null 2>&1 && break; sleep 2; done'
                        dockerBuildxPush(
                                image: env.IMG + ':' + env.TAG,
                                cacheRef: env.CACHE_REF,
                                dockerfile: env.DOCKERFILE_PATH,
                                context: env.CTX,
                                metadataFile: 'image-meta.json'
                        )
                        sh """
                            set -e
                            DIG=\$(tr -d '[:space:]' < image-meta.json | grep -oE '"containerimage.digest":"sha256:[0-9a-f]{64}"' | grep -oE 'sha256:[0-9a-f]{64}' | head -1)
                            test -n "\${DIG}"
                            echo "==> image: ${env.IMG}@\${DIG}"
                        """
                    }
                    sleep time: 5, unit: 'SECONDS'
                    echo "build & push complete for ${env.IMG}:${env.TAG}"
                }
            }
        }
    }
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
            String pyInstall = install ?: 'if [ -f requirements.txt ]; then pip install --no-cache-dir -r requirements.txt; elif [ -f pyproject.toml ]; then pip install --no-cache-dir .; elif [ -f setup.py ]; then pip install --no-cache-dir -e .; else r=$(ls */requirements.txt 2>/dev/null | head -1); if [ -n "$r" ]; then pip install --no-cache-dir -r "$r"; else echo "no python manifest - skipping install"; fi; fi'
            String pyStart = PythonLaunch.startLine(fw, p, (Map<String, String>) (d.sources ?: [:]), start)
            return """FROM python:3.12-slim
WORKDIR /app
COPY . .
RUN ${pyInstall}
ENV PORT=${p}
ENV PYTHONUNBUFFERED=1
EXPOSE ${p}
CMD ["sh","-c","${pyStart.replace('\\', '\\\\').replace('"', '\\"')}"]
"""

        case 'go':
            int p = port ?: 8080
            return """FROM golang:1.25 AS build
WORKDIR /app
COPY . .
RUN CGO_ENABLED=0 go build -o /app/server .
FROM gcr.io/distroless/base-debian12
COPY --from=build /app/server /server
EXPOSE ${p}
CMD ["/server"]
"""

        default:
            return null
    }
}

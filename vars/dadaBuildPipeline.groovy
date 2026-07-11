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

def call(Map config = [:]) {
    pipeline {
        agent {
            kubernetes {
                cloud 'self-managed'
                defaultContainer 'jnlp'
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
                    sh 'set -e; rm -rf src; git clone --depth 1 --branch "$branch" "$repo" src'
                }
            }
            stage('build & push') {
                steps {
                    script {
                        env.TAG = (params.branch + '-b' + env.BUILD_NUMBER).replaceAll('[^A-Za-z0-9_.-]', '-')
                        env.IMG = 'nexus.dada-tuda.ru/' + env.PROJ + '/' + env.APP
                        env.CTX = 'src/' + (env.ROOTDIR ?: '.')
                        env.CACHE_REF = env.IMG + ':buildcache-' + (params.branch ?: 'main').replaceAll('[^A-Za-z0-9_.-]', '-')

                        def dfPath = env.CTX + '/Dockerfile'
                        if (fileExists(dfPath)) {
                            echo "using repo Dockerfile at ${dfPath}"
                        } else {
                            def fw = (params.detected_framework ?: '').trim()
                            def df = renderDockerfile(fw, [
                                install: (params.install_cmd ?: '').trim(),
                                build  : (params.build_cmd ?: '').trim(),
                                start  : (params.start_cmd ?: '').trim(),
                                output : (params.output_dir ?: '').trim(),
                                port   : parsePort(params.app_port),
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
                                dockerfile: env.CTX + '/Dockerfile',
                                context: env.CTX
                        )
                        sh """
                            set -e
                            DIG=\$(docker buildx imagetools inspect ${env.IMG}:${env.TAG} --format '{{.Manifest.Digest}}')
                            echo "==> image: ${env.IMG}@\${DIG}"
                        """
                    }
                }
            }
        }
    }
}

// parsePort returns the numeric port or 0 when the parameter is empty/garbage.
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
            sb << "CMD ${start ?: 'npm start'}\n"
            return sb.toString()

        // Static / SPA: build, then serve the output dir with nginx.
        case ['react', 'vite', 'static']:
            return """FROM node:20-bookworm AS build
WORKDIR /app
RUN corepack enable
COPY . .
RUN ${install ?: 'npm install'}
RUN ${build ?: 'npm run build'}
FROM nginx:alpine
COPY --from=build /app/${output} /usr/share/nginx/html
EXPOSE 80
"""

        case ['fastapi', 'django', 'flask', 'python']:
            int p = port ?: 8000
            return """FROM python:3.12-slim
WORKDIR /app
COPY . .
RUN ${install ?: 'pip install --no-cache-dir -r requirements.txt'}
ENV PORT=${p}
EXPOSE ${p}
CMD ${start ?: 'python app.py'}
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

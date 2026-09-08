def call(Map config) {
    properties([disableConcurrentBuilds(abortPrevious: true)])

    def commitAuthor
    def commitMessage
    def currentStageName = "bootstrap"

    def PROJECT_NAME = ((config.projectName ?: '') as String).trim()
    if (!PROJECT_NAME) {
        error("config.projectName is required")
    }

    def GO_VER = (config.get('go', '1.22') as String).trim()
    def BUILDER_IMAGE = ((config.get('builderImage', "golang:${GO_VER}-bookworm") ?: "golang:${GO_VER}-bookworm") as String).trim()

    def SPLIT_DEV_AND_PROD = config.get('env-values', false)
    def DEPLOY_MODE = config.get('deploy', 'image')
    def deploy_build = DEPLOY_MODE == "image"

    // Module-relative path of the main package to compile into the image, and
    // the resulting binary name. Defaults suit a single-main repo; override per
    // project (e.g. mainPkg:'./cmd/agentsynchub-mcp').
    def MAIN_PKG = ((config.get('mainPkg', '.') ?: '.') as String).trim()
    def BIN_NAME = ((config.get('binName', 'app') ?: 'app') as String).trim()

    // CI-failure -> AgentSync autofix webhook is on by default for every go
    // service; pass empty strings explicitly to opt a project out.
    def FAILURE_WEBHOOK_URL = ((config.get('failureWebhookUrl', 'https://agent.dada-tuda.ru/v1/agentsync/events/ci-failed') ?: '') as String).trim()
    def FAILURE_WEBHOOK_TOKEN_CREDENTIAL_ID = ((config.get('failureWebhookTokenCredentialId', 'agentsync-ci-webhook-token') ?: '') as String).trim()
    def FAILURE_WEBHOOK_AUTH_SCHEME = ((config.get('failureWebhookAuthScheme', 'Bearer') ?: 'Bearer') as String).trim()

    def OVERRIDE_VALUES = config.get('values_override', '')
    def DOCKERFILE_OVERRIDE = ((config.get('dockerfile', 'Dockerfile') ?: 'Dockerfile') as String).trim()
    def DOCKER_CONTEXT = ((config.get('dockerContext', '.') ?: '.') as String).trim()
    // Go services deploy through the infra (argo-infra) repo by default, not the
    // helm/go/services/* path — there is no go services tree in dada-argo. Pass
    // infra:false + values_override only if a project lives somewhere else.
    def infra = config.get("infra", true)
    def INFRA_PROJECT = ((config.get('infraProject', 'example-project') ?: 'example-project') as String).trim()
    def INFRA_ENV = ((config.get('infraEnv', 'prod') ?: 'prod') as String).trim()

    kubePodTemplate(
            builderProfile: 'go',
            builderImage: BUILDER_IMAGE,
            withDocker: true,
            goCacheClaim: ((config.get('goCacheClaim', '') ?: '') as String).trim(),
    ) {

            def runStage = { String stageName, Closure body ->
                currentStageName = stageName
                stage(stageName) {
                    body()
                }
            }

            try {
                runStage('Checkout') {
                    checkout scm
                    commitAuthor = sh(script: "git log -1 --pretty=format:'%an'", returnStdout: true).trim()
                    commitMessage = sh(script: "git log -1 --pretty=format:'%s'", returnStdout: true).trim()
                }

                container('builder') {
                    runStage('Toolchain') {
                        sh '''
                            set -eux
                            # Survive transient cluster DNS flaps (overloaded single CoreDNS):
                            # retry network ops with backoff that outlasts a CoreDNS restart window.
                            retry() { i=0; until "$@"; do i=$((i+1)); [ "$i" -ge 6 ] && { echo "FATAL: '$*' failed after $i attempts" >&2; return 1; }; echo "[net-retry $i/6] '$*' failed (likely transient DNS), sleeping 15s..." >&2; sleep 15; done; }
                            retry python3 -c "import os,urllib.request; p='/tools/yq'; urllib.request.urlretrieve('https://github.com/mikefarah/yq/releases/download/v4.40.5/yq_linux_amd64', p); os.chmod(p,0o755)" \
                              || retry sh -c 'wget -qO /tools/yq https://github.com/mikefarah/yq/releases/download/v4.40.5/yq_linux_amd64 && chmod 0755 /tools/yq'
                            /tools/yq --version
                            id
                            hostname
                            go version
                            go env GOPATH GOMODCACHE GOCACHE
                        '''
                    }

                    runStage('Build / Vet / Test') {
                        sh """
                            set -eux
                            retry() { i=0; until "\$@"; do i=\$((i+1)); [ "\$i" -ge 6 ] && { echo "FATAL: '\$*' failed after \$i attempts" >&2; return 1; }; echo "[net-retry \$i/6] '\$*' failed (likely transient DNS), sleeping 15s..." >&2; sleep 15; done; }

                            if [ ! -f go.mod ]; then
                                echo "No go.mod found"
                                exit 1
                            fi

                            retry go mod download
                            go build ./...
                            go vet ./...

                            if go test -list '.*' ./... 2>/dev/null | grep -q .; then
                                echo "✓ Tests detected — running go test"
                                go test -race -count=1 ./...
                            else
                                echo "⚠️  No Go tests found — skipping go test"
                            fi
                        """
                    }
                }

                if (!isPR() && deploy_build && (env.BRANCH_NAME == 'develop' || env.BRANCH_NAME == 'master')) {
                    container('docker') {
                        runStage('Docker Deploy && Deploy to ArgoCD') {
                            def values
                            if (OVERRIDE_VALUES != '') {
                                values = OVERRIDE_VALUES
                            } else {
                                if (infra) {
                                    values = "clusters/beget-prod/projects/${INFRA_PROJECT}/environments/${INFRA_ENV}/apps/${PROJECT_NAME}/values.yaml"
                                } else {
                                    if (!SPLIT_DEV_AND_PROD) {
                                        values = "helm/go/services/${PROJECT_NAME}/values.yaml"
                                    } else {
                                        if (env.BRANCH_NAME == 'master') {
                                            values = "helm/go/services/${PROJECT_NAME}/values-prod.yaml"
                                        } else {
                                            values = "helm/go/services/${PROJECT_NAME}/values-dev.yaml"
                                        }
                                    }
                                }
                            }

                            def dockerfile = DOCKERFILE_OVERRIDE
                            if (!fileExists(dockerfile)) {
                                writeFile file: dockerfile, text: libraryResource('templates/Dockerfile.go')
                            }
                            if (!fileExists('.dockerignore')) {
                                writeFile file: '.dockerignore', text: libraryResource('templates/.dockerignore')
                            }

                            def result = makeDockerImageInfo(PROJECT_NAME, 'dada', '1.0.0')
                            def image = result.getName() + ':' + result.getTag()
                            def cacheRef = result.getName() + ':buildcache-' + (env.BRANCH_NAME ?: 'main').replaceAll('[^A-Za-z0-9_.-]', '-')

                            dockerBuildxPush(
                                    image: image,
                                    cacheRef: cacheRef,
                                    dockerfile: dockerfile,
                                    context: DOCKER_CONTEXT,
                                    buildArgs: [
                                            "GO_VERSION=${GO_VER}",
                                            "MAIN_PKG=${MAIN_PKG}",
                                            "BIN_NAME=${BIN_NAME}",
                                    ]
                            )

                            def repoUrl = 'https://github.com/DadaDevelopment/dada-argo.git'
                            def branch = 'develop'
                            def authType = 'https'
                            def credentialsId = 'gh-token'

                            if (infra) {
                                branch = 'console-migration'
                                repoUrl = 'https://github.com/DadaDevelopment/argo-infra.git'
                            }

                            def pinnedTag = result.getTag() + '@' + resolveImageDigest(image: image)

                            withEnv([
                                    "PATH=/tools:${env.PATH}"
                            ]) {
                                deployToArgo(repoUrl,
                                        values,
                                        pinnedTag,
                                        commitAuthor,
                                        commitMessage,
                                        branch,
                                        credentialsId,
                                        authType
                                )
                            }
                        }
                    }
                } else {
                    echo "Docker deploy skipped"
                }

            } catch (err) {
                currentBuild.result = 'FAILURE'
                _maybeSendCiFailureWebhook(
                        projectName: PROJECT_NAME,
                        webhookUrl: FAILURE_WEBHOOK_URL,
                        webhookTokenCredentialId: FAILURE_WEBHOOK_TOKEN_CREDENTIAL_ID,
                        webhookAuthScheme: FAILURE_WEBHOOK_AUTH_SCHEME,
                        failedStage: currentStageName,
                        failureSummary: err.toString(),
                        branch: _pipelineBranchName(),
                        jobName: env.JOB_NAME,
                        buildNumber: env.BUILD_NUMBER,
                        buildUrl: env.BUILD_URL,
                        traceId: env.BUILD_TAG,
                        repoUrl: _safeGitValue("git config --get remote.origin.url"),
                        commitSha: _safeGitValue("git rev-parse HEAD"),
                )
                throw err
            }
    }
}

def call(Map config) {
    properties([disableConcurrentBuilds(abortPrevious: true)])

    def commitAuthor
    def commitMessage
    def currentStageName = "bootstrap"

    def PROJECT_NAME = config.projectName
    def PYTHON_VER = (config.get('python', '3.12') as String).trim()
    def SPLIT_DEV_AND_PROD = config.get('env-values', false)
    def DEPLOY_MODE = config.get('deploy', 'image')
    def deploy_build = DEPLOY_MODE == "image"
    def deploy_package = DEPLOY_MODE == "dist"
    // CI-failure -> AgentSync autofix webhook is on by default for every python
    // service; pass empty strings explicitly to opt a project out.
    def FAILURE_WEBHOOK_URL = ((config.get('failureWebhookUrl', 'https://agent.dada-tuda.ru/v1/agentsync/events/ci-failed') ?: '') as String).trim()
    def FAILURE_WEBHOOK_TOKEN_CREDENTIAL_ID = ((config.get('failureWebhookTokenCredentialId', 'agentsync-ci-webhook-token') ?: '') as String).trim()
    def FAILURE_WEBHOOK_AUTH_SCHEME = ((config.get('failureWebhookAuthScheme', 'Bearer') ?: 'Bearer') as String).trim()
    def OVERRIDE_VALUES =  config.get('values_override', '')
    def BUILDER_IMAGE = "ghcr.io/astral-sh/uv:python${PYTHON_VER}-bookworm-slim"
    // Match the reachable Debian mirror used by service Docker builds; allow an override.
    def APT_MIRROR = (config.get('aptMirror', 'https://mirror.yandex.ru') as String).trim().replaceAll('/+$', '')
    def infra = config.get("infra", false)
    def INFRA_PROJECT = ((config.get('infraProject', 'example-project') ?: 'example-project') as String).trim()
    def INFRA_ENV = ((config.get('infraEnv', 'prod') ?: 'prod') as String).trim()
    def INFRA_CLUSTER = ((config.get('infraCluster', 'beget-prod') ?: 'beget-prod') as String).trim()
    def INFRA_BRANCH = ((config.get('infraBranch', 'console-migration') ?: 'console-migration') as String).trim()
    def CONSOLE_DEPLOY_TOKEN_CREDENTIAL_ID = ((config.get('consoleDeployTokenCredentialId', '') ?: '') as String).trim()
    def CONSOLE_URL = ((config.get('consoleUrl', 'https://console.dada-tuda.ru') ?: 'https://console.dada-tuda.ru') as String).trim()
    kubePodTemplate(
            builderProfile: 'python',
            builderImage: BUILDER_IMAGE,
            withDocker: true,
            // docker:29-dind regression: dockerd's embedded BuildKit "session
            // healthcheck" turns FATAL on "only one connection allowed" during a
            // heavy build (observed deterministically on dada-agent at the 300+
            // node-package apt step ~65s in) and the daemon self-shuts-down ->
            // docker build dies -> pod Failed -> Jenkins ABORTED. NOT memory/eviction
            // (dind exits 0 on graceful shutdown). Pin to docker:24, the only in-repo
            // proven-good tag (spring runs it in prod); its dockerd does not fatally
            // abort on this healthcheck. Match cli to dind to avoid API skew.
            dockerDindImage: 'docker:24-dind',
            dockerCliImage: 'docker:24-cli',
    ) {

            withEnv(["PATH=${env.HOME}/.local/bin:${env.PATH}"]) {
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

                    boolean useUv = fileExists("pyproject.toml")
                    boolean usePip = fileExists("requirements.txt")

                    container('builder') {
                        runStage("Toolchain") {
                            withEnv(["APT_MIRROR=${APT_MIRROR}"]) {
                                sh '''
                                set -eux
                                # Survive transient cluster DNS flaps (overloaded single CoreDNS):
                                # retry network ops with backoff that outlasts a CoreDNS restart window.
                                retry() { i=0; until "$@"; do i=$((i+1)); [ "$i" -ge 6 ] && { echo "FATAL: '$*' failed after $i attempts" >&2; return 1; }; echo "[net-retry $i/6] '$*' failed (likely transient DNS), sleeping 15s..." >&2; sleep 15; done; }
                                retry python3 -c "import os,socket,urllib.request; socket.setdefaulttimeout(60); p='/tools/yq'; urllib.request.urlretrieve('https://github.com/mikefarah/yq/releases/download/v4.40.5/yq_linux_amd64', p); os.chmod(p,0o755)"
                                /tools/yq --version
                                apt_mirror_write() { python3 -c "import pathlib,sys; mirror=sys.argv[1]; files=[pathlib.Path('/etc/apt/sources.list'),*pathlib.Path('/etc/apt/sources.list.d').glob('*.sources'),*pathlib.Path('/etc/apt/sources.list.d').glob('*.list')]; [(p.write_text(p.read_text().replace('http://deb.debian.org',mirror).replace('https://deb.debian.org',mirror))) for p in files if p.is_file() and mirror]" "$1"; }
                                # Neither mirror is reliable long-term: yandex sometimes
                                # serves stale bookworm-security metadata (builds 91-93),
                                # the Debian CDN times out from the agents (build 76).
                                # Try the configured primary, fall back to the other one.
                                case "$APT_MIRROR" in
                                  *yandex*) APT_FALLBACK='https://deb.debian.org' ;;
                                  *) APT_FALLBACK='https://mirror.yandex.ru' ;;
                                esac
                                apt_mirror_write "$APT_MIRROR"
                                if ! retry apt-get -o APT::Update::Error-Mode=any update; then
                                    echo "[apt-mirror] $APT_MIRROR failed; falling back to $APT_FALLBACK" >&2
                                    apt_mirror_write "$APT_FALLBACK"
                                    apt-get -o APT::Update::Error-Mode=any update
                                fi
                                if ! retry apt-get install -y gcc python3-dev libpq-dev; then
                                    echo "[apt-mirror] package download from $APT_MIRROR failed; falling back to $APT_FALLBACK" >&2
                                    apt_mirror_write "$APT_FALLBACK"
                                    apt-get -o APT::Update::Error-Mode=any update
                                    apt-get install -y gcc python3-dev libpq-dev
                                fi
                              '''
                            }
                            withEnv(["PATH=/tools:${env.PATH}"]) {
                                sh '''
                                set -eux
                                mkdir -p "$WORKSPACE/.bin"
                                id
                                hostname
                                python --version
                                uv --version
                                yq --version
                            '''
                            }
                        }

                        if (usePip) {
                            runStage('Install deps - pip') {
                                withCredentials([file(credentialsId: 'pip-nexus-settings', variable: 'PIP_SETTINGS')]) {
                                    sh """
                                        set -eux
                                        retry() { i=0; until "\$@"; do i=\$((i+1)); [ "\$i" -ge 6 ] && { echo "FATAL: '\$*' failed after \$i attempts" >&2; return 1; }; echo "[net-retry \$i/6] '\$*' failed (likely transient DNS), sleeping 15s..." >&2; sleep 15; done; }
                                        python -m venv venv
                                        . venv/bin/activate
                                        export PIP_CONFIG_FILE='${PIP_SETTINGS}'
                                        retry uv pip install --upgrade pip setuptools wheel
                                        retry uv pip install -r requirements.txt

                                        if [ -f requirements-dev.txt ]; then
                                            retry uv pip install -r requirements-dev.txt
                                        fi
                                    """
                                }
                            }
                        } else {
                            echo "no requirements.txt - skip"
                        }

                        if (useUv) {
                            runStage('Install deps - uv') {
                                withCredentials([file(credentialsId: 'uv-nexus-settings', variable: 'UV_SETTINGS')]) {
                                    withEnv(["UV_PYTHON=${PYTHON_VER}"]) {
                                        sh '''
                                            set -eux
                                            retry() { i=0; until "$@"; do i=$((i+1)); [ "$i" -ge 6 ] && { echo "FATAL: '$*' failed after $i attempts" >&2; return 1; }; echo "[net-retry $i/6] '$*' failed (likely transient DNS), sleeping 15s..." >&2; sleep 15; done; }
                                            uv --version
                                            uv_sync_locked() { uv sync --locked --config-file="$UV_SETTINGS" --all-extras --python "$UV_PYTHON"; }
                                            if [ ! -f uv.lock ]; then
                                                echo '[uv-lock] no uv.lock in the repo - syncing unlocked; run `uv lock` in the repo and commit uv.lock to make builds reproducible' >&2
                                                sed -i "s/^requires-python *= *\"[^\"]*\"/requires-python = \"==$UV_PYTHON.*\"/" pyproject.toml
                                                retry uv sync --config-file="$UV_SETTINGS" --all-extras --python "$UV_PYTHON"
                                            elif out=$(uv_sync_locked 2>&1); then
                                                printf '%s\n' "$out"
                                            else
                                                printf '%s\n' "$out"
                                                if printf '%s' "$out" | grep -qE 'needs to be updated|requires-python'; then
                                                    echo '[uv-lock] uv.lock is stale vs pyproject.toml - failing fast after one resolve, syncing unlocked; run `uv lock` in the repo and commit uv.lock' >&2
                                                    sed -i "s/^requires-python *= *\"[^\"]*\"/requires-python = \"==$UV_PYTHON.*\"/" pyproject.toml
                                                    retry uv sync --config-file="$UV_SETTINGS" --all-extras --python "$UV_PYTHON"
                                                else
                                                    echo '[uv-lock] uv sync failure does not look lock-related - retrying as transient' >&2
                                                    retry uv_sync_locked
                                                fi
                                            fi
                                            cp -f "$UV_SETTINGS" ./uv.toml
                                        '''
                                    }
                                }
                            }
                        } else {
                            echo "no pyproject.toml - skip"
                        }

                        runStage("Test & Lint") {
                            sh """
                                set -eux
                                retry() { i=0; until "\$@"; do i=\$((i+1)); [ "\$i" -ge 6 ] && { echo "FATAL: '\$*' failed after \$i attempts" >&2; return 1; }; echo "[net-retry \$i/6] '\$*' failed (likely transient DNS), sleeping 15s..." >&2; sleep 15; done; }
                                if [ -f ".venv/bin/activate" ]; then
                                    . .venv/bin/activate
                                elif [ -f "venv/bin/activate" ]; then
                                    . venv/bin/activate
                                else
                                    echo "⚠️  No virtualenv (.venv or venv) found — cannot run tests"
                                    exit 0
                                fi

                                export PYTHONPATH="\$PWD:\${PYTHONPATH:-}"

                                echo "Checking for tests..."
                                TEST_DIRS=\$(find . -maxdepth 2 -type d \\( -name "tests" -o -name "test" \\) || true)
                                TEST_FILES=\$(find . -maxdepth 3 -type f \\( -name "test_*.py" -o -name "*_test.py" \\) || true)

                                if [ -z "\$TEST_DIRS" ] && [ -z "\$TEST_FILES" ]; then
                                    echo "⚠️  No tests found — skipping pytest"
                                else
                                    echo "✓ Tests detected — running pytest"
                                    retry uv pip install pytest pytest-cov pytest-asyncio testcontainers
                                    uv run pytest --junitxml test-results.xml
                                fi
                            """

                            if (fileExists('test-results.xml')) {
                                junit testResults: 'test-results.xml', skipPublishingChecks: true
                            }

                            if (!isPR() && env.BRANCH_NAME == 'develop') {
                                def scannerHome = tool 'SonarScanner'

                                if (!fileExists('sonar-project.properties')) {
                                    echo "⚠️  sonar-project.properties not found — skipping Sonar scan"
                                } else {
                                    def props = readFile('sonar-project.properties')
                                    def missing = []

                                    ['sonar.projectKey', 'sonar.sources'].each { key ->
                                        if (!props.contains("${key}=")) {
                                            missing << key
                                        }
                                    }

                                    if (!missing.isEmpty()) {
                                        echo "⚠️  Missing mandatory Sonar properties: ${missing.join(', ')} — skipping Sonar scan"
                                    } else {
                                        withSonarQubeEnv() {
                                            sh "${scannerHome}/bin/sonar-scanner"
                                        }
                                    }
                                }
                            }
                        }

                        if (deploy_package) {
                            runStage("Build Python") {
                                script {
                                    sh """
                                        set -x
                                        retry() { i=0; until "\$@"; do i=\$((i+1)); [ "\$i" -ge 6 ] && { echo "FATAL: '\$*' failed after \$i attempts" >&2; return 1; }; echo "[net-retry \$i/6] '\$*' failed (likely transient DNS), sleeping 15s..." >&2; sleep 15; done; }
                                        if [ -f ".venv/bin/activate" ]; then
                                            . .venv/bin/activate
                                        elif [ -f "venv/bin/activate" ]; then
                                            . venv/bin/activate
                                        else
                                            echo "⚠️  No virtualenv (.venv or venv) found — cannot build"
                                            exit 1
                                        fi
                                        retry uv pip install build
                                        python -m build
                                        if [ -f uv.toml ]; then
                                            retry uv sync --config-file=uv.toml --all-extras --python ${PYTHON_VER} --no-extra dev
                                        fi
                                    """
                                }
                            }

                            if (!isPR()) {
                                runStage("PythonDeploy") {
                                    sh """
                                        set -eux
                                        retry() { i=0; until "\$@"; do i=\$((i+1)); [ "\$i" -ge 6 ] && { echo "FATAL: '\$*' failed after \$i attempts" >&2; return 1; }; echo "[net-retry \$i/6] '\$*' failed (likely transient DNS), sleeping 15s..." >&2; sleep 15; done; }
                                        retry uv pip install twine
                                        retry uv run twine upload -r nexus dist/* --config-file .pypirc
                                    """
                                }
                            }
                        }
                    }

                    if (!isPR() && deploy_build ) {
                        container('docker') {
                            runStage('Docker Deploy && Deploy to ArgoCD') {

                                def values
                                if (OVERRIDE_VALUES!=''){
                                    values = OVERRIDE_VALUES
                                }else {
                                    if (infra) {
                                        values = "clusters/${INFRA_CLUSTER}/projects/${INFRA_PROJECT}/environments/${INFRA_ENV}/apps/${PROJECT_NAME}/values.yaml"
                                    } else {
                                        if (!SPLIT_DEV_AND_PROD) {
                                            values = "helm/python/services/${PROJECT_NAME}/values.yaml"
                                        } else {
                                            if (env.BRANCH_NAME == 'master') {
                                                values = "helm/python/services/${PROJECT_NAME}/values-prod.yaml"
                                            } else {
                                                values = "helm/python/services/${PROJECT_NAME}/values-dev.yaml"
                                            }
                                        }
                                    }
                                }

                                def dockerfile = config.get('dockerfile', 'Dockerfile')
                                if (!fileExists(dockerfile)) {
                                    writeFile file: dockerfile, text: libraryResource('templates/Dockerfile.python')
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
                                        context: '.',
                                        push: false,
                                        buildArgs: ["PYTHON_VERSION=${PYTHON_VER}"]
                                )

                                def repoUrl = 'https://github.com/DadaDevelopment/dada-argo.git'
                                def branch = 'develop'
                                def authType = 'https'
                                def credentialsId = 'gh-token'

                                if (infra) {
                                    branch = INFRA_BRANCH
                                    repoUrl = 'https://github.com/DadaDevelopment/argo-infra.git'
                                }

                                def pinnedTag
                                def pinnedImage

                                withEnv(["PATH=/tools:${env.PATH}"]) {
                                    doDeploy(image)

                                    def digest = resolveImageDigest(image: image)
                                    pinnedTag = result.getTag() + '@' + digest
                                    pinnedImage = image + '@' + digest

                                    deployToArgo(repoUrl,
                                            values,
                                            pinnedTag,
                                            commitAuthor,
                                            commitMessage,
                                            branch,
                                            credentialsId,
                                            authType,)
                                }

                                if (CONSOLE_DEPLOY_TOKEN_CREDENTIAL_ID != '') {
                                    dadaConsoleDeploy(
                                            image: pinnedImage,
                                            tokenCredentialsId: CONSOLE_DEPLOY_TOKEN_CREDENTIAL_ID,
                                            consoleUrl: CONSOLE_URL,
                                    )
                                }
                            }
                        }
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
}

import groovy.json.JsonOutput

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

    def FAILURE_WEBHOOK_URL = ((config.get('failureWebhookUrl', '') ?: '') as String).trim()
    def FAILURE_WEBHOOK_TOKEN_CREDENTIAL_ID = ((config.get('failureWebhookTokenCredentialId', '') ?: '') as String).trim()
    def FAILURE_WEBHOOK_AUTH_SCHEME = ((config.get('failureWebhookAuthScheme', 'Bearer') ?: 'Bearer') as String).trim()

    def REGISTRY_CREDENTIALS_ID = ((config.get('registryCredentialsId', '') ?: '') as String).trim()
    def BUILDER_IMAGE = ((config.get('builderImage',
            "ghcr.io/astral-sh/uv:python${PYTHON_VER}-bookworm-slim") ?: '') as String).trim()
    def DOCKER_CLI_IMAGE = ((config.get('dockerCliImage', 'docker:29-cli') ?: '') as String).trim()

    def podLabel = "kubeagent-${env.JOB_BASE_NAME ?: 'job'}-${env.BUILD_NUMBER ?: 'manual'}"
            .replaceAll('[^A-Za-z0-9-]', '-')
            .toLowerCase()

    def buildxName = "bx-${env.JOB_NAME ?: 'job'}-${env.BUILD_NUMBER ?: '0'}"
            .replaceAll('[^A-Za-z0-9-]', '-')
            .toLowerCase()

    podTemplate(
            cloud: 'self-managed',
            label: podLabel,
            namespace: 'devops-tools',
            serviceAccount: 'jenkins-admin',
            yaml: """
apiVersion: v1
kind: Pod
metadata:
  labels:
    app.kubernetes.io/name: jenkins-agent
    app.kubernetes.io/part-of: jenkins-common
    app.kubernetes.io/managed-by: jenkins
spec:
  securityContext:
    fsGroup: 1000
  volumes:
    - name: workspace-volume
      emptyDir: {}
    - name: pip-cache
      emptyDir: {}
    - name: uv-cache
      emptyDir: {}
    - name: docker-config
      emptyDir: {}
  containers:
    - name: jnlp
      image: jenkins/inbound-agent:latest
      tty: true
      workingDir: /home/jenkins/agent
      volumeMounts:
        - name: workspace-volume
          mountPath: /home/jenkins/agent

    - name: builder
      image: ${BUILDER_IMAGE}
      command: ['cat']
      tty: true
      workingDir: /home/jenkins/agent
      env:
        - name: HOME
          value: /tmp
        - name: PIP_CACHE_DIR
          value: /tmp/.cache/pip
        - name: UV_CACHE_DIR
          value: /tmp/.cache/uv
        - name: XDG_CACHE_HOME
          value: /tmp/.cache
        - name: PATH
          value: /home/jenkins/.local/bin:/usr/local/bin:/usr/bin:/bin
      volumeMounts:
        - name: workspace-volume
          mountPath: /home/jenkins/agent
        - name: pip-cache
          mountPath: /tmp/.cache/pip
        - name: uv-cache
          mountPath: /tmp/.cache/uv

    - name: docker
      image: ${DOCKER_CLI_IMAGE}
      command: ['sh', '-c', 'cat']
      tty: true
      workingDir: /home/jenkins/agent
      env:
        - name: HOME
          value: /tmp
        - name: DOCKER_CONFIG
          value: /tmp/.docker
      volumeMounts:
        - name: workspace-volume
          mountPath: /home/jenkins/agent
        - name: docker-config
          mountPath: /tmp/.docker
"""
    ) {
        node(podLabel) {
            cleanWs()

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
                boolean usePip = !useUv && fileExists("requirements.txt")

                container('builder') {
                    runStage("Toolchain") {
                        sh """
                            set -eux
                            id
                            hostname
                            python --version
                            uv --version
                        """
                    }

                    if (usePip) {
                        runStage('Install deps - pip') {
                            withCredentials([file(credentialsId: 'pip-nexus-settings', variable: 'PIP_SETTINGS')]) {
                                sh """
                                    set -eux
                                    python -m venv venv
                                    . venv/bin/activate
                                    python -m ensurepip
                                    export PIP_CONFIG_FILE='${PIP_SETTINGS}'
                                    python -m pip install --upgrade pip setuptools wheel
                                    python -m pip install -r requirements.txt

                                    if [ -f requirements-dev.txt ]; then
                                        python -m pip install -r requirements-dev.txt
                                    fi
                                """
                            }
                        }
                    } else if (useUv) {
                        runStage('Install deps - uv') {
                            withCredentials([file(credentialsId: 'uv-nexus-settings', variable: 'UV_SETTINGS')]) {
                                sh """
                                    set -eux
                                    uv sync -v --config-file='${UV_SETTINGS}' --all-extras --python ${PYTHON_VER}
                                    cp -f '${UV_SETTINGS}' ./uv.toml
                                """
                            }
                        }
                    } else {
                        echo "No dependency manifest found: neither pyproject.toml nor requirements.txt"
                    }

                    runStage("Test & Lint") {
                        if (useUv) {
                            sh """
                                set -eux
                                export PYTHONPATH="\$PWD:\${PYTHONPATH:-}"

                                TEST_DIRS=\$(find . -maxdepth 2 -type d \\( -name "tests" -o -name "test" \\) || true)
                                TEST_FILES=\$(find . -maxdepth 3 -type f \\( -name "test_*.py" -o -name "*_test.py" \\) || true)

                                if [ -z "\$TEST_DIRS" ] && [ -z "\$TEST_FILES" ]; then
                                    echo "⚠️  No tests found — skipping pytest"
                                else
                                    uv pip install --python .venv/bin/python pytest pytest-cov pytest-asyncio testcontainers
                                    uv run pytest --junitxml test-results.xml
                                fi
                            """
                        } else if (usePip) {
                            sh """
                                set -eux
                                . venv/bin/activate
                                export PYTHONPATH="\$PWD:\${PYTHONPATH:-}"

                                TEST_DIRS=\$(find . -maxdepth 2 -type d \\( -name "tests" -o -name "test" \\) || true)
                                TEST_FILES=\$(find . -maxdepth 3 -type f \\( -name "test_*.py" -o -name "*_test.py" \\) || true)

                                if [ -z "\$TEST_DIRS" ] && [ -z "\$TEST_FILES" ]; then
                                    echo "⚠️  No tests found — skipping pytest"
                                else
                                    python -m pip install pytest pytest-cov pytest-asyncio testcontainers
                                    python -m pytest --junitxml test-results.xml
                                fi
                            """
                        } else {
                            echo "No dependency environment created — skipping tests"
                        }

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
                            if (useUv) {
                                sh """
                                    set -eux
                                    uv pip install --python .venv/bin/python build twine
                                    uv run python -m build
                                """
                            } else if (usePip) {
                                sh """
                                    set -eux
                                    . venv/bin/activate
                                    python -m pip install build twine
                                    python -m build
                                """
                            } else {
                                error("Cannot build Python package: no pyproject.toml or requirements.txt path initialized")
                            }
                        }

                        if (!isPR()) {
                            runStage("PythonDeploy") {
                                sh """
                                    set -eux
                                    if [ -f ".venv/bin/activate" ]; then
                                        . .venv/bin/activate
                                    elif [ -f "venv/bin/activate" ]; then
                                        . venv/bin/activate
                                    fi
                                    twine upload -r nexus dist/* --config-file .pypirc
                                """
                            }
                        }
                    }
                }

                if (!isPR() && deploy_build && (env.BRANCH_NAME == 'develop' || env.BRANCH_NAME == 'master')) {
                    container('docker') {
                        runStage('Image Buildx Build & Push + Deploy to ArgoCD') {
                            def values
                            if (!SPLIT_DEV_AND_PROD) {
                                values = "helm/python/services/${PROJECT_NAME}/values.yaml"
                            } else {
                                values = env.BRANCH_NAME == 'master'
                                        ? "helm/python/services/${PROJECT_NAME}/values-prod.yaml"
                                        : "helm/python/services/${PROJECT_NAME}/values-dev.yaml"
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
                            def imageRepo = result.getName()

                            if (REGISTRY_CREDENTIALS_ID) {
                                withCredentials([usernamePassword(
                                        credentialsId: REGISTRY_CREDENTIALS_ID,
                                        usernameVariable: 'REGISTRY_USER',
                                        passwordVariable: 'REGISTRY_PASSWORD'
                                )]) {
                                    sh """
                                        set +x
                                        REGISTRY_HOST=\$(echo '${imageRepo}' | cut -d/ -f1)
                                        echo "\$REGISTRY_PASSWORD" | docker login "\$REGISTRY_HOST" -u "\$REGISTRY_USER" --password-stdin
                                    """
                                }
                            }

                            sh """
                                set -eux
                                docker buildx version

                                docker buildx create \
                                  --name ${buildxName} \
                                  --driver kubernetes \
                                  --driver-opt namespace=devops-tools,rootless=true \
                                  --bootstrap \
                                  --use

                                docker buildx inspect --bootstrap

                                docker buildx build \
                                  --builder ${buildxName} \
                                  --platform linux/amd64 \
                                  --build-arg PYTHON_VERSION=${PYTHON_VER} \
                                  -f ${dockerfile} \
                                  -t ${image} \
                                  --push \
                                  .

                                docker buildx rm ${buildxName} || true
                            """

                            deployToArgo(
                                    'ssh://git@bitbucket.dada-tuda.ru:7999/dada/dada-argo.git',
                                    values,
                                    result.getTag(),
                                    commitAuthor,
                                    commitMessage,
                                    'develop'
                            )
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
            } finally {
                container('docker') {
                    sh "docker buildx rm ${buildxName} || true"
                }
            }
        }
    }
}
def _pipelineBranchName() {
    return ((env.CHANGE_BRANCH ?: env.BRANCH_NAME ?: '') as String).trim()
}

def _safeGitValue(String cmd) {
    try {
        return sh(script: "${cmd} || true", returnStdout: true).trim()
    } catch (ignored) {
        return ''
    }
}

def _maybeSendCiFailureWebhook(Map args) {
    def webhookUrl = ((args.webhookUrl ?: '') as String).trim()
    if (!webhookUrl) {
        echo "ci failure webhook disabled for ${args.projectName}"
        return
    }

    def repoUrl = ((args.repoUrl ?: '') as String).trim()
    def branch = ((args.branch ?: '') as String).trim()
    def commitSha = ((args.commitSha ?: '') as String).trim()
    def jobName = ((args.jobName ?: '') as String).trim()
    def buildNumber = ((args.buildNumber ?: '') as String).trim()
    def failedStage = (((args.failedStage ?: 'unknown') as String).trim()) ?: 'unknown'
    if (!repoUrl || !branch || !commitSha || !jobName || !buildNumber) {
        echo "ci failure webhook skipped because required Jenkins/git metadata is missing"
        return
    }

    def payload = [
            repo_url       : repoUrl,
            branch         : branch,
            commit_sha     : commitSha,
            job_name       : jobName,
            build_number   : buildNumber,
            build_url      : ((args.buildUrl ?: '') as String).trim(),
            failed_stage   : failedStage,
            failure_summary: (((args.failureSummary ?: '') as String).trim()) ?: 'jenkins pipeline failed',
            trace_id       : (((args.traceId ?: '') as String).trim()) ?: "${jobName}#${buildNumber}",
            idempotency_key: "${jobName}:${buildNumber}:${failedStage}",
    ]

    def payloadFile = '.ci-failed-payload.json'
    writeFile file: payloadFile, text: JsonOutput.prettyPrint(JsonOutput.toJson(payload))

    try {
        def tokenCredentialId = ((args.webhookTokenCredentialId ?: '') as String).trim()
        def authScheme = (((args.webhookAuthScheme ?: 'Bearer') as String).trim()) ?: 'Bearer'
        if (tokenCredentialId) {
            withCredentials([string(credentialsId: tokenCredentialId, variable: 'CI_FAILURE_WEBHOOK_TOKEN')]) {
                withEnv([
                        "CI_FAILURE_WEBHOOK_URL=${webhookUrl}",
                        "CI_FAILURE_WEBHOOK_AUTH_SCHEME=${authScheme}",
                ]) {
                    sh '''
                        set +x
                        curl --silent --show-error --fail \
                          --header "Content-Type: application/json" \
                          --header "Authorization: ${CI_FAILURE_WEBHOOK_AUTH_SCHEME} ${CI_FAILURE_WEBHOOK_TOKEN}" \
                          --data @.ci-failed-payload.json \
                          "${CI_FAILURE_WEBHOOK_URL}" >/dev/null
                    '''
                }
            }
        } else {
            withEnv(["CI_FAILURE_WEBHOOK_URL=${webhookUrl}"]) {
                sh '''
                    set +x
                    curl --silent --show-error --fail \
                      --header "Content-Type: application/json" \
                      --data @.ci-failed-payload.json \
                      "${CI_FAILURE_WEBHOOK_URL}" >/dev/null
                '''
            }
        }
        echo "ci failure webhook delivered to ${webhookUrl}"
    } catch (hookErr) {
        echo "ci failure webhook failed: ${hookErr}"
    } finally {
        sh "rm -f ${payloadFile}"
    }
}
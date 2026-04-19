import groovy.json.JsonOutput

def call(Map config) {
    properties([disableConcurrentBuilds(abortPrevious: true)])
    def commitAuthor
    def commitMessage
    def currentStageName = "bootstrap"

    def PROJECT_NAME = config.projectName
    def PYTHON_VER = config.get('python', '3.12')
    def SPLIT_DEV_AND_PROD = config.get('env-values', false)
    def DEPLOY_MODE = config.get('deploy', 'image')
    def deploy_build = DEPLOY_MODE == "image"
    def deploy_package = DEPLOY_MODE == "dist"
    def FAILURE_WEBHOOK_URL = ((config.get('failureWebhookUrl', '') ?: '') as String).trim()
    def FAILURE_WEBHOOK_TOKEN_CREDENTIAL_ID = ((config.get('failureWebhookTokenCredentialId', '') ?: '') as String).trim()
    def FAILURE_WEBHOOK_AUTH_SCHEME = ((config.get('failureWebhookAuthScheme', 'Bearer') ?: 'Bearer') as String).trim()

    def python = 'python' + PYTHON_VER
    def podLabel = "kubeagent-${env.BUILD_NUMBER ?: 'manual'}"
    def builderImage = "astral/uv:python${PYTHON_VER}-bookworm-slim"
    def dockerCliImage = "docker:28-cli"

    podTemplate(
            cloud: 'self-managed',
            label: podLabel,
            namespace: 'devops-tools',
            yaml: """
apiVersion: v1
kind: Pod
metadata:
  labels:
    app.kubernetes.io/name: jenkins-agent
    app.kubernetes.io/part-of: jenkins-common
    app.kubernetes.io/managed-by: jenkins
spec:
  serviceAccountName: jenkins-admin
  securityContext:
    runAsUser: 1000
    runAsGroup: 1000
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
      image: ${builderImage}
      command: ['cat']
      tty: true
      workingDir: /home/jenkins/agent
      env:
        - name: HOME
          value: /home/jenkins
        - name: PIP_CACHE_DIR
          value: /home/jenkins/.cache/pip
        - name: UV_CACHE_DIR
          value: /home/jenkins/.cache/uv
        - name: XDG_CACHE_HOME
          value: /home/jenkins/.cache
        - name: PATH
          value: /home/jenkins/.local/bin:/usr/local/bin:/usr/bin:/bin
      volumeMounts:
        - name: workspace-volume
          mountPath: /home/jenkins/agent
        - name: pip-cache
          mountPath: /home/jenkins/.cache/pip
        - name: uv-cache
          mountPath: /home/jenkins/.cache/uv

    - name: docker
      image: ${dockerCliImage}
      command: ['cat']
      tty: true
      workingDir: /home/jenkins/agent
      env:
        - name: HOME
          value: /home/jenkins
        - name: DOCKER_CONFIG
          value: /home/jenkins/.docker
      volumeMounts:
        - name: workspace-volume
          mountPath: /home/jenkins/agent
        - name: docker-config
          mountPath: /home/jenkins/.docker
"""
    ) {
        node(podLabel) {
            cleanWs()

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
                        sh(script: "whoami | cat")
                        sh(script: "hostname | cat")
                    }

                    container('builder') {
                        runStage("Install Python $python") {
                            sh """
                                set -eux
                                command -v ${python}
                                ${python} --version
                                python --version
                                pip --version || true
                                uv --version
                            """
                        }

                        if (fileExists("requirements.txt")) {
                            runStage('Install deps - pip') {
                                withCredentials([file(credentialsId: 'pip-nexus-settings', variable: 'PIP_SETTINGS')]) {
                                    sh """
                                        set -eux
                                        ${python} -m venv venv
                                        . venv/bin/activate
                                        python -m ensurepip
                                        export PIP_CONFIG_FILE='${PIP_SETTINGS}'
                                        python -m pip install -r requirements.txt

                                        if [ -f requirements-dev.txt ]; then
                                            python -m pip install -r requirements-dev.txt
                                        fi
                                    """
                                }
                            }
                        } else {
                            echo "no requirements.txt - skip"
                        }

                        if (fileExists("pyproject.toml")) {
                            runStage('Install deps - uv') {
                                withCredentials([file(credentialsId: 'uv-nexus-settings', variable: 'UV_SETTINGS')]) {
                                    sh """
                                        set -eux
                                        sed -i "s/^requires-python *= *\\"==[0-9.]*\\"/requires-python = \\"==${PYTHON_VER}\\"/" pyproject.toml || true
                                        uv sync -v --config-file=${env.UV_SETTINGS} --all-extras --python ${PYTHON_VER}
                                        cp -f ${env.UV_SETTINGS} ./uv.toml
                                    """
                                }
                            }
                        } else {
                            echo "no pyproject.toml - skip"
                        }

                        runStage("Test & Lint") {
                        if(fileExists(".venv/bin/activate")){
                            sh '. .venv/bin/activate'
                        } else if(fileExists("venv/bin/activate")) {
                            sh '. venv/bin/activate'
                        } else {
                            echo "⚠️  No virtualenv (.venv or venv) found — cannot run tests"
                            return
                        }
                            sh """
                                set -eux
                                export PYTHONPATH="\$PWD:\${PYTHONPATH:-}"

                                echo "Checking for tests..."
                                TEST_DIRS=\$(find . -maxdepth 2 -type d \\( -name "tests" -o -name "test" \\))
                                TEST_FILES=\$(find . -maxdepth 3 -type f \\( -name "test_*.py" -o -name "*_test.py" \\))

                                if [ -z "\$TEST_DIRS" ] && [ -z "\$TEST_FILES" ]; then
                                    echo "⚠️  No tests found — skipping pytest"
                                else
                                    echo "✓ Tests detected — running pytest"
                                ${fileExists("pyproject.toml") ? 'uv' : ''} pip install pytest pytest-cov pytest-asyncio testcontainers
                                    ${fileExists("pyproject.toml") ? 'uv run pytest' : 'python -m pytest'} --junitxml test-results.xml
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


                    if (!isPR() && deploy_build && (env.BRANCH_NAME == 'develop' || env.BRANCH_NAME == 'master')) {
                        container('docker') {
                            runStage('Docker Deploy && Deploy to ArgoCD') {
                                def values
                                if (!SPLIT_DEV_AND_PROD) {
                                    values = "helm/python/services/${PROJECT_NAME}/values.yaml"
                                } else {
                                    if (env.BRANCH_NAME == 'master') {
                                        values = "helm/python/services/${PROJECT_NAME}/values-prod.yaml"
                                    } else {
                                        values = "helm/python/services/${PROJECT_NAME}/values-dev.yaml"
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
                                sh """
                                    docker build --build-arg PYTHON_VERSION=${PYTHON_VER} -f ${dockerfile} -t ${image} .
                                """
                                doDeploy(image)
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
                        if (deploy_package) {
                            runStage("Build Python") {
                                sh """
                                    set -x
                                    ${fileExists("pyproject.toml") ? 'uv' : 'python -m'} pip install build
                                    if [ -f ".venv/bin/activate" ]; then
                                        . .venv/bin/activate
                                    elif [ -f "venv/bin/activate" ]; then
                                        . venv/bin/activate
                                    else
                                        echo "⚠️  No virtualenv (.venv or venv) found — cannot build"
                                        exit 1
                                    fi
                                    python -m build
                                    if [ -f uv.toml ]; then
                                        uv sync -v --config-file=uv.toml --all-extras --python ${PYTHON_VER} --no-extra dev
                                    fi
                                """
                            }

                            if (!isPR()) {
                                runStage("PythonDeploy") {
                                    sh """
                                        twine upload -r nexus dist/* --config-file .pypirc
                                    """
                                }
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
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
    def OVERRIDE_VALUES =  config.get('values_override', '')
    def BUILDER_IMAGE = "ghcr.io/astral-sh/uv:python${PYTHON_VER}-bookworm-slim"
    def DOCKER_CLI_IMAGE = "docker:29-cli"
    def DOCKER_DIND_IMAGE = "docker:29-dind"

    def podLabel = "kubeagent-${env.JOB_BASE_NAME ?: 'job'}-${env.BUILD_NUMBER ?: 'manual'}"
            .replaceAll('[^A-Za-z0-9-]', '-')
            .toLowerCase()
    def agentName = "kubeagent-${env.JOB_BASE_NAME}-${env.BUILD_NUMBER}-${UUID.randomUUID().toString().take(6)}"

    podTemplate(
            cloud: 'self-managed',
            label: podLabel,
            namespace: 'devops-tools',
            serviceAccount: 'jenkins-admin',
            yaml: """
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
  securityContext:
    fsGroup: 1000
  volumes:
    - name: workspace-volume
      emptyDir:
        sizeLimit: 3Gi
    - name: pip-cache
      emptyDir:
        sizeLimit: 1Gi
    - name: uv-cache
      emptyDir:
        sizeLimit: 1Gi
    - name: docker-graph-storage
      emptyDir:
        sizeLimit: 5Gi
    - name: docker-certs
      emptyDir: {}
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
        limits:
          cpu: "300m"
          memory: "256Mi"

      volumeMounts:
        - name: workspace-volume
          mountPath: /home/jenkins/agent
        - name: tools-volume
          mountPath: /tools

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
      resources:
        requests:
          cpu: "250m"
          memory: "512Mi"
        limits:
          cpu: "1500m"
          memory: "1536Mi"

      volumeMounts:
        - name: workspace-volume
          mountPath: /home/jenkins/agent
        - name: pip-cache
          mountPath: /tmp/.cache/pip
        - name: uv-cache
          mountPath: /tmp/.cache/uv
        - name: tools-volume
          mountPath: /tools

    - name: docker
      image: ${DOCKER_CLI_IMAGE}
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
        limits:
          cpu: "250m"
          memory: "128Mi"
      volumeMounts:
        - name: workspace-volume
          mountPath: /home/jenkins/agent
        - name: tools-volume
          mountPath: /tools

    - name: dind
      image: ${DOCKER_DIND_IMAGE}
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
          cpu: "250m"
          memory: "512Mi"
        limits:
          cpu: "1500m"
          memory: "1536Mi"
      volumeMounts:
        - name: docker-graph-storage
          mountPath: /var/lib/docker
        - name: docker-certs
          mountPath: /certs
        - name: workspace-volume
          mountPath: /home/jenkins/agent
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
                    }

                    boolean useUv = fileExists("pyproject.toml")
                    boolean usePip = fileExists("requirements.txt")

                    container('builder') {
                        runStage("Toolchain") {
                            sh '''
                                set -eux
                                python3 -c "import os,urllib.request; p='/tools/yq'; urllib.request.urlretrieve('https://github.com/mikefarah/yq/releases/download/v4.40.5/yq_linux_amd64', p); os.chmod(p,0o755)"
                                /tools/yq --version
                              '''
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
                        } else {
                            echo "no requirements.txt - skip"
                        }

                        if (useUv) {
                            runStage('Install deps - uv') {
                                withCredentials([file(credentialsId: 'uv-nexus-settings', variable: 'UV_SETTINGS')]) {
                                    sh """
                                        set -eux
                                        sed -i "s/^requires-python *= *\\"==[0-9.]*\\"/requires-python = \\"==${PYTHON_VER}\\"/" pyproject.toml || true
                                        uv sync --link-mode=copy --config-file='${UV_SETTINGS}' --all-extras --python ${PYTHON_VER}
                                        cp -f '${UV_SETTINGS}' ./uv.toml
                                    """
                                }
                            }
                        } else {
                            echo "no pyproject.toml - skip"
                        }

                        runStage("Test & Lint") {
                            if (fileExists(".venv/bin/activate")) {
                                sh '. .venv/bin/activate'
                            } else if (fileExists("venv/bin/activate")) {
                                sh '. venv/bin/activate'
                            } else {
                                echo "⚠️  No virtualenv (.venv or venv) found — cannot run tests"
                                return
                            }

                            sh """
                                set -eux
                                export PYTHONPATH="\$PWD:\${PYTHONPATH:-}"

                                echo "Checking for tests..."
                                TEST_DIRS=\$(find . -maxdepth 2 -type d \\( -name "tests" -o -name "test" \\) || true)
                                TEST_FILES=\$(find . -maxdepth 3 -type f \\( -name "test_*.py" -o -name "*_test.py" \\) || true)

                                if [ -z "\$TEST_DIRS" ] && [ -z "\$TEST_FILES" ]; then
                                    echo "⚠️  No tests found — skipping pytest"
                                else
                                    echo "✓ Tests detected — running pytest"
                                    ${fileExists("pyproject.toml") ? 'uv' : 'python -m'} pip install pytest pytest-cov pytest-asyncio testcontainers
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

                        if (deploy_package) {
                            runStage("Build Python") {
                                script {
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
                                            uv sync --link-mode=copy --config-file=uv.toml --all-extras --python ${PYTHON_VER} --no-extra dev
                                        fi
                                    """
                                }
                            }

                            if (!isPR()) {
                                runStage("PythonDeploy") {
                                    sh """
                                        set -eux
                                        twine upload -r nexus dist/* --config-file .pypirc
                                    """
                                }
                            }
                        }
                    }

                    if (!isPR() && deploy_build && (env.BRANCH_NAME == 'develop' || env.BRANCH_NAME == 'master')) {
                        container('docker') {
                            runStage('Docker Deploy && Deploy to ArgoCD') {

                                def values
                                if (OVERRIDE_VALUES!=''){
                                    values = OVERRIDE_VALUES
                                }else {
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
                                    set -eux
                                    docker version
                                    docker build --build-arg PYTHON_VERSION=${PYTHON_VER} -f ${dockerfile} -t ${image} .
                                """

                                withCredentials([sshUserPrivateKey(credentialsId: 'bitbucket-jenkins', keyFileVariable: 'SSH_KEY')]) {
                                    sh '''
                                        set -eux
                                        mkdir -p /tmp/.ssh
                                        chmod 700 /tmp/.ssh
                                        ssh-keyscan -p 7999 -H bitbucket.dada-tuda.ru > /tmp/.ssh/known_hosts
                                        chmod 644 /tmp/.ssh/known_hosts
                                    '''

                                    def gitSshCmd = "ssh -i ${SSH_KEY} -p 7999 -o IdentitiesOnly=yes -o UserKnownHostsFile=/tmp/.ssh/known_hosts -o StrictHostKeyChecking=yes"

                                    withEnv(["PATH=/tools:${env.PATH}", "GIT_SSH_COMMAND=${gitSshCmd}"]) {
                                        doDeploy(image)
                                        deployToArgo('ssh://git@bitbucket.dada-tuda.ru:7999/dada/dada-argo.git',
                                                values,
                                                result.getTag(),
                                                commitAuthor,
                                                commitMessage,
                                                'develop')
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

def call(Map config) {
    properties([disableConcurrentBuilds(abortPrevious: true)])
    def commitAuthor
    def commitMessage

    def PROJECT_NAME = config.projectName
    def PYTHON_VER = config.get('python', '3.12')
    def python = 'python' + PYTHON_VER
    node {
        cleanWs()

        stage('Checkout') {
            checkout scm
            commitAuthor = sh(script: "git log -1 --pretty=format:'%an'", returnStdout: true).trim()
            commitMessage = sh(script: "git log -1 --pretty=format:'%s'", returnStdout: true).trim()
        }

        stage("Install Python $python") {
            sh """
                if ! command -v $python >/dev/null 2>&1; then
                    apt-get update
                    apt-get install -y software-properties-common
                    add-apt-repository -y ppa:deadsnakes/ppa
                    apt-get update
                    apt-get install -y $python $python-venv $python-dev
                else
                    echo "$python already installed"
                fi
            """
        }


        stage('Install deps') {
            withCredentials([file(credentialsId: 'pip-nexus-settings', variable: 'PIP_SETTINGS')]) {
                withCredentials([usernamePassword(credentialsId: 'docker-nexus-admin-psws', usernameVariable: 'NEXUS_USER', passwordVariable: 'NEXUS_PASS')]) {
                    sh """
                        $python -m venv venv
                        . venv/bin/activate
                        python -m pip install --upgrade pip
                        export PIP_CONFIG_FILE='${PIP_SETTINGS}'
                        pip config debug
                        python -m pip install -r requirements.txt
                        if [ -f requirements-dev.txt ]; then
                            python -m pip install -r requirements-dev.txt
                        else
                            python -m pip install pytest pytest-cov pytest-asyncio testcontainers
                        fi
                    """
                }
            }
        }

        stage('Tests') {
            sh """
                . venv/bin/activate
                python -m pytest
                echo "export PYTHONPATH=\$PWD:\$PYTHONPATH" >> venv/bin/activate
            """
            if (!isPR() && env.BRANCH_NAME == 'develop') {
                def scannerHome = tool 'SonarScanner'
                withSonarQubeEnv() {
                    sh "${scannerHome}/bin/sonar-scanner"
                }
            }
        }

        if (!isPR() && (env.BRANCH_NAME == 'develop' || env.BRANCH_NAME == 'master')) {
            stage('Docker Deploy && Deploy to ArgoCD') {
                def values
                if (env.BRANCH_NAME == 'master') {
                    values = "helm/python/services/${PROJECT_NAME}/values-prod.yaml"
                } else {
                    values = "helm/python/services/${PROJECT_NAME}/values-dev.yaml"
                }
                def result = makeDockerImageInfo($ { PROJECT_NAME }, 'dada', '1.0.0')
                def image = result.getName() + ':' + result.getTag()
                doDeploy(image)
                deployToArgo('ssh://git@bitbucket.dada-tuda.ru:7999/dada/dada-argo.git',
                        values,
                        result.getTag,
                        commitAuthor,
                        commitMessage,
                        'develop')
            }

        }
    }
}

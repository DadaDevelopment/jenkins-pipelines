def call(Map config) {
    properties([disableConcurrentBuilds(abortPrevious: true)])
    def commitAuthor
    def commitMessage

    def PROJECT_NAME = config.projectName

    node {
        cleanWs()

        stage('Checkout') {
            checkout scm
            commitAuthor = sh(script: "git log -1 --pretty=format:'%an'", returnStdout: true).trim()
            commitMessage = sh(script: "git log -1 --pretty=format:'%s'", returnStdout: true).trim()
        }

        stage('Install Python 3.12') {
            sh '''
            if ! command -v python3.12 >/dev/null 2>&1; then
                apt-get update
                apt-get install -y software-properties-common
                add-apt-repository -y ppa:deadsnakes/ppa
                apt-get update
                apt-get install -y python3.12 python3.12-venv python3.12-dev
            else
                echo "Python 3.12 already installed"
            fi
        '''
        }


        stage('Install deps') {
            withCredentials([file(credentialsId: 'pip-nexus-settings', variable: 'PIP_SETTINGS')]) {
                withCredentials([usernamePassword(credentialsId: 'nexus Ci creds', usernameVariable: 'NEXUS_USER', passwordVariable: 'NEXUS_PASS')]) {
                    sh """
                python3.12 -m venv venv
                . venv/bin/activate
                python -m pip install --upgrade pip
                export PIP_CONFIG_FILE='${PIP_SETTINGS}'
                pip config debug
                python -m pip install -r requirements.txt
                if [ -f requirements-dev.txt ]; then
                    python -m pip install -r requirements-dev.txt
                else
                   
            . venv/bin/activate
            python -m pytest
        """
                } python -m pip install pytest pytest - cov pytest -asyncio testcontainers
                fi
                echo "export PYTHONPATH=$PWD:\$PYTHONPATH" >> venv / bin / activate
                """
                }
            }
        }
        stage('Tests') {
            sh """

                if (!isPR() && env.BRANCH_NAME == 'develop') {
                    script {
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

                        script {
                            def result = makeDockerImageInfo($ { PROJECT_NAME }, 'dada', '1.0.0')
                            echo result
                            def image = result.getName() + ':' + result.getTag()
                            echo image
                            withCredentials([usernamePassword(credentialsId: 'nexus Ci creds', usernameVariable: 'NEXUS_USER', passwordVariable: 'NEXUS_PASS')]) {
                                withCredentials([usernamePassword(credentialsId: 'docker-nexus-admin-psws', usernameVariable: 'DOCKER_USERNAME', passwordVariable: 'DOCKER_LOGIN')]) {
                                    sh """
                            docker build --build-arg NEXUS_USER=${NEXUS_USER} --build-arg NEXUS_PASS=${NEXUS_PASS} -t ${image} .
                            echo "\${DOCKER_LOGIN}" | docker login nexus.dada-tuda.ru -u \${DOCKER_USERNAME} --password-stdin
                            docker push ${image}
                            docker rmi ${image} || true
                        """
                                }
                            }
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
        }
    }
}





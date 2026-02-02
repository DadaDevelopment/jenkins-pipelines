def call(Map config) {
    properties([
            disableConcurrentBuilds(abortPrevious: true)
    ])
    def commitAuthor
    def commitMessage

    def PROJECT_NAME = config.projectName
    def SUBMODULE = config.get('submodule', '')
    def buildPath = SUBMODULE ? "-pl ${SUBMODULE}" : ""
    node {
        cleanWs()
        useJdk(config.get('jdk', 'openjdk-17-jdk'))

        stage('Checkout') {
            checkout scm
            commitAuthor = sh(script: "git log -1 --pretty=format:'%an'", returnStdout: true).trim()
            commitMessage = sh(script: "git log -1 --pretty=format:'%s'", returnStdout: true).trim()
        }


        if (!isPR() && env.BRANCH_NAME == 'develop') {
            stage('Build && Test && SonarQube Analysis') {
                sonar("clean install verify sonar:sonar " +
                        "-Dsonar.projectKey=${config.sonarKey} " +
                        "-Dsonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml " +
                        "-Dsonar.projectName=${PROJECT_NAME}")
            }
        }else{
            stage('Build && Test') {
                mvn("clean install -Dmaven.repo.local=/root/.m2/repository/ -ntp -U ${buildPath}")
            }
        }

        if (!isPR() && (env.BRANCH_NAME == 'develop' || env.BRANCH_NAME == 'master')) {
            stage('Docker Deploy && Deploy to ArgoCD') {
                def values
                if(env.BRANCH_NAME == 'master') {
                    values = "helm/spring/services/${PROJECT_NAME}/values-prod.yaml"
                } else {
                    values = "helm/spring/services/${PROJECT_NAME}/values-dev.yaml"
                }

                def result = deployDockerImageToNexus("${PROJECT_NAME}-service",
                        'dada',
                        "-Dmaven.repo.local=/root/.m2/repository/ -ntp -U ${buildPath}")
                deployToArgo(
                        'ssh://git@bitbucket.dada-tuda.ru:7999/dada/dada-argo.git',
                        values,
                        result,
                        commitAuthor,
                        commitMessage,
                        'develop'
                )
            }
        }
    }
}


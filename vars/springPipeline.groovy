def call(Map config) {
    properties([
            disableConcurrentBuilds(abortPrevious: true)
    ])
    def commitAuthor
    def commitMessage

    def PROJECT_NAME = config.projectName
    def SUBMODULE = config.get('submodule', '')
    node {
        cleanWs()

        if (config.get('jdk') != null || 'openjdk-17-jdk'.equalsIgnoreCase(config.get('jdk'))) {
            def jdkHome = tool name: config.get('jdk'), type: 'jdk'
            //env.JAVA_HOME = "${jdkHome}"
            //env.PATH = "${jdkHome}/bin:${env.PATH}"
            useJdk(jdkHome)
        }

        stage('Checkout') {
            checkout scm
            commitAuthor = sh(script: "git log -1 --pretty=format:'%an'", returnStdout: true).trim()
            commitMessage = sh(script: "git log -1 --pretty=format:'%s'", returnStdout: true).trim()
        }


        stage('Build && Test') {
            mvn("clean install -U -Dmaven.repo.local=/root/.m2/repository/ -ntp")
        }
        stage('archieveArtifacts') {
                archiveArtifacts artifacts: '**/target/*.jar', fingerprint: true
        }

        if (!isPR() && env.BRANCH_NAME == 'develop') {
            stage('SonarQube Analysis') {
                sonar("clean verify sonar:sonar " +
                        "-Dsonar.projectKey=${config.sonarKey} " +
                        "-Dsonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml " +
                        "-Dsonar.projectName=${PROJECT_NAME}")
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
                def buildPath = SUBMODULE ? "-pl ${SUBMODULE}" : ""
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


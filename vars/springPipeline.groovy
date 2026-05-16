def call(Map config) {
    properties([
            disableConcurrentBuilds(abortPrevious: true)
    ])
    def commitAuthor
    def commitMessage

    def PROJECT_NAME = ((config.projectName ?: '') as String).trim()
    if (!PROJECT_NAME) {
        error("config.projectName is required")
    }
    def SUBMODULE = config.get('submodule', '')
    def JDK_IMAGE_VERSION = (((config.get('jdk', 'openjdk-17-jdk') as String).replaceAll('[^0-9]', '') ?: '17') as String).trim()
    def BUILDER_IMAGE = ((config.get('builderImage', "maven:3.9.9-eclipse-temurin-${JDK_IMAGE_VERSION}") ?: "maven:3.9.9-eclipse-temurin-${JDK_IMAGE_VERSION}") as String).trim()
    kubePodTemplate(
            builderProfile: 'maven',
            builderImage: BUILDER_IMAGE
    ) {

            stage('Checkout') {
                checkout scm
                commitAuthor = sh(script: "git log -1 --pretty=format:'%an'", returnStdout: true).trim()
                commitMessage = sh(script: "git log -1 --pretty=format:'%s'", returnStdout: true).trim()
            }

            container('builder') {
                if (!isPR() && env.BRANCH_NAME == 'develop') {
                    stage('Build && Test && SonarQube Analysis') {
                        sonar("clean install verify sonar:sonar -Dmaven.repo.local=/root/.m2/repository/ -ntp -U " +
                                "-Dsonar.projectKey=${config.sonarKey} " +
                                "-Dsonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml " +
                                "-Dsonar.projectName=${PROJECT_NAME}")
                    }
                } else {
                    stage('Build && Test') {
                        mvn("clean install -Dmaven.repo.local=/root/.m2/repository/ -ntp -U")
                    }
                }

                if (!isPR() && (env.BRANCH_NAME == 'develop' || env.BRANCH_NAME == 'master')) {
                    def buildPath = SUBMODULE ? " -pl ${SUBMODULE}" : ""

                    stage('Docker Deploy && Deploy to ArgoCD') {
                        def values
                        if (env.BRANCH_NAME == 'master') {
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
}

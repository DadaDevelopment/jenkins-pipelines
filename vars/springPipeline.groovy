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
    def infra = config.get("infra", false)
    def INFRA_PROJECT = ((config.get('infraProject', 'example-project') ?: 'example-project') as String).trim()
    def INFRA_ENV = ((config.get('infraEnv', 'prod') ?: 'prod') as String).trim()
    def SUBMODULE = config.get('submodule', '')
    def JDK_IMAGE_VERSION = (((config.get('jdk', 'openjdk-17-jdk') as String).replaceAll('[^0-9]', '') ?: '17') as String).trim()
    def BUILDER_IMAGE = ((config.get('builderImage', "maven:3.9.9-eclipse-temurin-${JDK_IMAGE_VERSION}") ?: "maven:3.9.9-eclipse-temurin-${JDK_IMAGE_VERSION}") as String).trim()
    kubePodTemplate(
            builderProfile: 'maven',
            builderImage: BUILDER_IMAGE,
            // Opt-in persistent Maven cache (shared RWX PVC). Repos pass
            // mavenCacheClaim: 'jenkins-m2-cache' to skip the ~30m cold download;
            // empty (default) keeps the per-build emptyDir.
            mavenCacheClaim: ((config.get('mavenCacheClaim', '') ?: '') as String).trim(),
            withDocker: true,
            // spring-boot:build-image pins Docker API v1.24; docker:29-dind requires
            // >=1.40 and rejects it. Pin an older dind that still serves 1.24 (only
            // affects spring builds; js/python use the modern docker:29-cli themselves).
            dockerDindImage: 'docker:24-dind',
            dockerCliImage: 'docker:24-cli'
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
                        def repoUrl
                        def branch
                        def credentialsId
                        def authType

                        if (infra) {
                            values = "clusters/beget-prod/projects/${INFRA_PROJECT}/environments/${INFRA_ENV}/apps/${PROJECT_NAME}/values.yaml"
                            repoUrl = 'https://github.com/DadaDevelopment/argo-infra.git'
                            branch = 'console-migration'
                            credentialsId = 'gh-token'
                            authType = 'https'
                        } else {
                            if (env.BRANCH_NAME == 'master') {
                                values = "helm/spring/services/${PROJECT_NAME}/values-prod.yaml"
                            } else {
                                values = "helm/spring/services/${PROJECT_NAME}/values-dev.yaml"
                            }
                            repoUrl = 'ssh://git@bitbucket.dada-tuda.ru:7999/dada/dada-argo.git'
                            branch = 'develop'
                            credentialsId = 'bitbucket-jenkins'
                            authType = 'ssh'
                        }

                        def result = deployDockerImageToNexus("${PROJECT_NAME}-service",
                                'dada',
                                "-Dmaven.repo.local=/root/.m2/repository/ -ntp -U ${buildPath}")

                        deployToArgo(
                                repoUrl,
                                values,
                                result,
                                commitAuthor,
                                commitMessage,
                                branch,
                                credentialsId,
                                authType
                        )
                    }
                }
    }
}
}

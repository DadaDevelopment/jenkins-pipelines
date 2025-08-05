def call(Map config) {
    properties([
            disableConcurrentBuilds(abortPrevious: true)
    ])

    def PROJECT_NAME = config.projectName
    def SUBMODULE = config.get('submodule', '')

    node {
        cleanWs()

        stage('Checkout') {
            checkout scm
        }

        stage('Build && Test') {
            mvn("clean install -U -Dmaven.repo.local=/root/.m2/repository/ -ntp")
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
            def repoName = env.BRANCH_NAME == 'master' ?
                    'ssh://git@bitbucket.dada-tuda.ru:7999/dada/prod-argo.git' :
                    'ssh://git@bitbucket.dada-tuda.ru:7999/dada/dada-argo.git'

            stage('Docker Deploy && Deploy to ArgoCD') {
                def buildPath = SUBMODULE ? "-pl ${SUBMODULE}" : ""
                def result = deployDockerImageToNexus(
                        PROJECT_NAME,
                        'dada',
                        "-Dmaven.repo.local=/root/.m2/repository/ -ntp -U ${buildPath}"
                )
                deployToArgo(
                        repoName,
                        "helm/spring/services/${PROJECT_NAME}/values.yaml",
                        result,
                        'develop'
                )
            }
        }
    }
}

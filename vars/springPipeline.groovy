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
        useJdk(config.get('jdk', 'openjdk-17-jdk'))

        stage('Checkout') {
            checkout scm
            commitAuthor = sh(script: "git log -1 --pretty=format:'%an'", returnStdout: true).trim()
            commitMessage = sh(script: "git log -1 --pretty=format:'%s'", returnStdout: true).trim()

        }
        stage('ENV DIAG (helps prove "same environment') {
            sh(label: "Env diag (checkout)",
                    script: '''
set -eu
echo "=== ENV (checkout) ==="
pwd
whoami
echo "HOME=$HOME"
echo "WORKSPACE=$WORKSPACE"
ls -la .
echo "======================"
''')
        }

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

        // PROVE warmup happened in THIS env (same HOME/workspace, same /root/.m2)
        stage('Assert Maven warmup in same env') {
            sh(label: "Assert local m2 has reactor artifacts",
                    script: '''
set -eu

echo "=== ENV (assert warmup) ==="
pwd
whoami
echo "HOME=$HOME"
echo "WORKSPACE=$WORKSPACE"

echo "=== Check local repo presence ==="
ls -la /root/.m2 || true
ls -la /root/.m2/repository || true

# Expect reactor parent installed locally after 'clean install'
if [ ! -d "/root/.m2/repository/dada/tuda/user-service" ]; then
  echo "ERROR: /root/.m2/repository/dada/tuda/user-service not found"
  echo "=> Maven install did NOT warm up local repo in this environment"
  exit 1
fi

echo "OK: Found /root/.m2/repository/dada/tuda/user-service"
ls -la /root/.m2/repository/dada/tuda/user-service || true

echo "List POMs (proof):"
find /root/.m2/repository/dada/tuda/user-service -name "*.pom" || true

echo "========================="
''')
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

                // Optional diagnostic: show if effective-pom still contains ${revision}
                // Not failing build here; it prints useful proof.
                if (SUBMODULE) {
                    sh(label: "Effective POM sanity (pre-image)",
                            script: """
set -eu
echo "=== Effective POM sanity (pre-image) ==="
mvn -Dmaven.repo.local=/root/.m2/repository/ -ntp -U ${buildPath} -DskipTests help:effective-pom \\
  | grep -n "<artifactId>user-service</artifactId>" -A2 || true
echo "======================================="
""")
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
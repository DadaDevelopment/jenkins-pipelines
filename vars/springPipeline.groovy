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

            sh(label: "Env diag (checkout)", script: """
                set -euxo pipefail
                echo "=== ENV (checkout) ==="
                pwd
                whoami
                echo "HOME=\$HOME"
                echo "WORKSPACE=\$WORKSPACE"
                ls -la .
                echo "======================"
            """)
        }


        if (!isPR() && env.BRANCH_NAME == 'develop') {
            stage('Build && Test && SonarQube Analysis') {
                sonar("clean install verify sonar:sonar -Dmaven.repo.local=/root/.m2/repository/ " +
                        "-Dsonar.projectKey=${config.sonarKey} " +
                        "-Dsonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml " +
                        "-Dsonar.projectName=${PROJECT_NAME}")
            }
        }else{
            stage('Build && Test') {
                mvn("clean install -Dmaven.repo.local=/root/.m2/repository/ -ntp -U")
            }
        }

        // --- PROVE "WARMUP" HAPPENED (fail-fast) ---
        stage('Assert Maven warmup in same env') {
            sh(label: "Assert local m2 has reactor parent", script: """
                set -euxo pipefail
                echo "=== ENV (assert warmup) ==="
                pwd
                whoami
                echo "HOME=\$HOME"
                echo "WORKSPACE=\$WORKSPACE"
                echo "=== Check local repo ==="
                ls -la /root/.m2 || true
                ls -la /root/.m2/repository || true

                # Your root project is user-service (pom). We expect its POM in local repo after install.
                # NOTE: version dir may vary; we just assert *something* exists.
                if [ ! -d "/root/.m2/repository/dada/tuda/user-service" ]; then
                  echo "ERROR: /root/.m2/repository/dada/tuda/user-service not found => warmup did NOT happen in this env"
                  exit 1
                fi

                echo "Found local parent dir:"
                ls -la /root/.m2/repository/dada/tuda/user-service

                # Optional: show any POMs found (proof)
                find /root/.m2/repository/dada/tuda/user-service -maxdepth 3 -name "*.pom" -o -name "user-service-*.pom" || true
                echo "========================="
            """)
        }

        if (!isPR() && (env.BRANCH_NAME == 'develop' || env.BRANCH_NAME == 'master')) {
            def buildPath = SUBMODULE ? " -pl ${SUBMODULE}" : ""
            stage('Docker Deploy && Deploy to ArgoCD') {
                def values
                if(env.BRANCH_NAME == 'master') {
                    values = "helm/spring/services/${PROJECT_NAME}/values-prod.yaml"
                } else {
                    values = "helm/spring/services/${PROJECT_NAME}/values-dev.yaml"
                }

                // --- Optional: extra proof that SUBMODULE resolves parent version (not ${revision}) ---
                // This is cheap and will immediately show if effective model still contains ${revision}.
                if (SUBMODULE) {
                    sh(label: "Effective POM sanity (pre-image)", script: """
                        set -euxo pipefail
                        mvn -Dmaven.repo.local=/root/.m2/repository/ -ntp -U ${buildPath} -DskipTests \
                          help:effective-pom | (grep -n "<artifactId>user-service</artifactId>" -A2 || true)
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


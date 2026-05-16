import groovy.json.JsonOutput

def call(Map config) {
    properties([disableConcurrentBuilds(abortPrevious: true)])

    def commitAuthor
    def commitMessage
    def currentStageName = "bootstrap"

    def PROJECT_NAME = ((config.projectName ?: '') as String).trim()
    if (!PROJECT_NAME) {
        error("config.projectName is required")
    }

    def NODE_VER = (config.get('node', '20.19.5') as String).trim()
    def YARN_VER = (config.get('yarn', '4.11.0') as String).trim()
    def PNPM_VER = (config.get('pnpm', '10.12.4') as String).trim()

    def SPLIT_DEV_AND_PROD = config.get('env-values', false)
    def DEPLOY_MODE = config.get('deploy', 'image')
    def deploy_build = DEPLOY_MODE == "image"

    def FAILURE_WEBHOOK_URL = ((config.get('failureWebhookUrl', '') ?: '') as String).trim()
    def FAILURE_WEBHOOK_TOKEN_CREDENTIAL_ID = ((config.get('failureWebhookTokenCredentialId', '') ?: '') as String).trim()
    def FAILURE_WEBHOOK_AUTH_SCHEME = ((config.get('failureWebhookAuthScheme', 'Bearer') ?: 'Bearer') as String).trim()

    def BUILDER_IMAGE = ((config.get('builderImage', 'node:20-bookworm') ?: 'node:20-bookworm') as String).trim()

    def OPENAPI_BASE_URL = ((config.get('openapiBaseUrl', 'https://profi-backend.dada-tuda.ru') ?: '') as String).trim()
    def OPENAPI_SYNC_SOURCE = ((config.get('openapiSyncSource', 'prod') ?: '') as String).trim()
    def VALUES_PATH_OVERRIDE = ((config.get('valuesPath', '') ?: '') as String).trim()
    def DOCKERFILE_OVERRIDE = ((config.get('dockerfile', 'Dockerfile') ?: 'Dockerfile') as String).trim()
    def DOCKER_CONTEXT = ((config.get('dockerContext', '.') ?: '.') as String).trim()

    kubePodTemplate(
            builderProfile: 'node',
            builderImage: BUILDER_IMAGE,
            withDocker: true,
    ) {

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

                container('builder') {
                    runStage('Toolchain') {
                        sh '''
                            set -eux
                            if command -v python3 >/dev/null 2>&1; then
                              python3 -c "import os,urllib.request; p='/tools/yq'; urllib.request.urlretrieve('https://github.com/mikefarah/yq/releases/download/v4.40.5/yq_linux_amd64', p); os.chmod(p,0o755)"
                            else
                              echo "python3 not found in builder image, cannot bootstrap /tools/yq"
                              exit 1
                            fi
                            /tools/yq --version
                        '''

                        withEnv(["PATH=/tools:${env.PATH}"]) {
                            sh """
                                set -eux
                                id
                                hostname
                                node --version
                                npm --version
                                corepack enable
                                corepack --version
                                yq --version
                            """
                        }
                    }

                    runStage('Install deps') {
                        sh """
                            set -eux
                            corepack enable

                            if [ -f yarn.lock ]; then
                              echo "Detected yarn"
                              corepack prepare yarn@${YARN_VER} --activate
                              yarn --version
                              if [ -f .yarnrc.yml ] || [ -d .yarn ]; then
                                yarn install --immutable || yarn install
                              else
                                yarn install --frozen-lockfile || yarn install
                              fi
                            elif [ -f pnpm-lock.yaml ]; then
                              echo "Detected pnpm"
                              corepack prepare pnpm@${PNPM_VER} --activate
                              pnpm --version
                              pnpm install --frozen-lockfile || pnpm install
                            elif [ -f package-lock.json ] || [ -f npm-shrinkwrap.json ]; then
                              echo "Detected npm lockfile"
                              npm ci || npm install
                            elif [ -f package.json ]; then
                              echo "Detected plain npm project"
                              npm install
                            else
                              echo "No package.json found"
                              exit 1
                            fi
                        """
                    }

                    runStage('Sync OpenAPI') {
                        withEnv([
                                "VITE_API_BASE=${OPENAPI_BASE_URL}",
                                "OPENAPI_SYNC_SOURCE=${OPENAPI_SYNC_SOURCE}"
                        ]) {
                            sh '''
                                set -eux

                                if [ ! -f package.json ]; then
                                  echo "No package.json — skip OpenAPI sync"
                                  exit 0
                                fi

                                has_script() {
                                  node -e "const p=require('./package.json'); process.exit(p.scripts && p.scripts['$1'] ? 0 : 1)"
                                }

                                if [ -f ./scripts/sync-openapi.mjs ]; then
                                  echo "[sync-openapi] VITE_API_BASE=${VITE_API_BASE}"
                                  echo "[sync-openapi] OPENAPI_SYNC_SOURCE=${OPENAPI_SYNC_SOURCE}"
                                  node ./scripts/sync-openapi.mjs
                                elif has_script sync-openapi; then
                                  echo "Running package script: sync-openapi"
                                  if [ -f yarn.lock ]; then
                                    yarn run sync-openapi
                                  elif [ -f pnpm-lock.yaml ]; then
                                    pnpm run sync-openapi
                                  else
                                    npm run sync-openapi
                                  fi
                                else
                                  echo "No OpenAPI sync script/file found — skip"
                                fi

                                if has_script api:generate; then
                                  echo "Running package script: api:generate"
                                  if [ -f yarn.lock ]; then
                                    yarn api:generate
                                  elif [ -f pnpm-lock.yaml ]; then
                                    pnpm run api:generate
                                  else
                                    npm run api:generate
                                  fi
                                else
                                  echo "No api:generate script — skip"
                                fi
                           '''
                        }
                    }

                    runStage('Typecheck / Lint / Build / Tests') {
                        withEnv(["VITE_API_BASE=${OPENAPI_BASE_URL}"]) {
                            sh '''
                                set -eux

                                has_script() {
                                  node -e "const p=require('./package.json'); process.exit(p.scripts && p.scripts['$1'] ? 0 : 1)"
                                }

                                run_pm() {
                                  script_name="$1"
                                  if [ -f yarn.lock ]; then
                                    yarn run "$script_name"
                                  elif [ -f pnpm-lock.yaml ]; then
                                    pnpm run "$script_name"
                                  else
                                    npm run "$script_name"
                                  fi
                                }

                                if has_script typecheck; then
                                  echo "Running typecheck"
                                  run_pm typecheck
                                else
                                  echo "No typecheck script — skip"
                                fi

                                if has_script lint; then
                                  echo "Running lint"
                                  run_pm lint
                                else
                                  echo "No lint script — skip"
                                fi

                                if has_script build; then
                                  echo "Running build"
                                  run_pm build
                                else
                                  echo "No build script — skip"
                                fi

                                echo "[tests] VITE_API_BASE=${VITE_API_BASE}"

                                real_failed=0
                                unit_failed=0
                                real_ran=0

                                if [ -f tests/integration/uiApiCrud.real.test.ts ]; then
                                  real_ran=1
                                  REAL_API_TESTS=1 npx vitest run tests/integration/uiApiCrud.real.test.ts || real_failed=1
                                fi

                                if [ -f tests/integration/generatedApiCrud.real.test.ts ]; then
                                  real_ran=1
                                  REAL_API_TESTS=1 npx vitest run tests/integration/generatedApiCrud.real.test.ts || real_failed=1
                                fi

                                if [ -f tests/integration/generatedApiCoverage.real.test.ts ]; then
                                  real_ran=1
                                  REAL_API_TESTS=1 npx vitest run tests/integration/generatedApiCoverage.real.test.ts || real_failed=1
                                fi

                                if has_script test; then
                                  REAL_API_TESTS=0 run_pm test || unit_failed=1
                                else
                                  echo "No test script — skip unit tests"
                                fi

                                if [ "$real_ran" = "0" ]; then
                                  echo "No real integration tests found — skip"
                                fi

                                if [ "$real_failed" = "1" ] || [ "$unit_failed" = "1" ]; then
                                  echo "Some JS tests failed"
                                  exit 1
                                fi
                            '''
                        }
                    }

                    runStage('Sonar (optional)') {
                        if (!isPR() && env.BRANCH_NAME == 'develop') {
                            def scannerHome = tool 'SonarScanner'

                            if (!fileExists('sonar-project.properties')) {
                                echo "⚠️ sonar-project.properties not found — skipping Sonar scan"
                            } else {
                                def props = readFile('sonar-project.properties')
                                def missing = []

                                ['sonar.projectKey', 'sonar.sources'].each { key ->
                                    if (!props.contains("${key}=")) {
                                        missing << key
                                    }
                                }

                                if (!missing.isEmpty()) {
                                    echo "⚠️ Missing mandatory Sonar properties: ${missing.join(', ')} — skipping Sonar scan"
                                } else {
                                    withSonarQubeEnv() {
                                        sh "${scannerHome}/bin/sonar-scanner"
                                    }
                                }
                            }
                        } else {
                            echo "Sonar skipped"
                        }
                    }
                }

                if (!isPR() && deploy_build && (env.BRANCH_NAME == 'develop' || env.BRANCH_NAME == 'master')) {
                    container('docker') {
                        runStage('Docker Deploy && Deploy to ArgoCD') {
                            def values
                            if (VALUES_PATH_OVERRIDE) {
                                values = VALUES_PATH_OVERRIDE
                            } else if (!SPLIT_DEV_AND_PROD) {
                                values = "helm/javascript/services/${PROJECT_NAME}/values.yaml"
                            } else {
                                if (env.BRANCH_NAME == 'master') {
                                    values = "helm/javascript/services/${PROJECT_NAME}/values-prod.yaml"
                                } else {
                                    values = "helm/javascript/services/${PROJECT_NAME}/values-dev.yaml"
                                }
                            }

                            def dockerfile = DOCKERFILE_OVERRIDE
                            if (!fileExists(dockerfile)) {
                                writeFile file: dockerfile, text: libraryResource('templates/Dockerfile.javascript')
                            }
                            if (!fileExists('.dockerignore')) {
                                writeFile file: '.dockerignore', text: libraryResource('templates/.dockerignore')
                            }

                            def result = makeDockerImageInfo(PROJECT_NAME, 'dada', '1.0.0')
                            def image = result.getName() + ':' + result.getTag()

                            sh """
                                set -eux
                                docker version
                                docker build -f ${dockerfile} -t ${image} ${DOCKER_CONTEXT}
                            """

                            withCredentials([
                                    usernamePassword(credentialsId: 'docker-nexus-admin-psws', usernameVariable: 'DOCKER_USERNAME', passwordVariable: 'DOCKER_LOGIN'),
                                    sshUserPrivateKey(credentialsId: 'bitbucket-jenkins', keyFileVariable: 'SSH_KEY')
                            ]) {
                                sh '''
                                    set -eux
                                    mkdir -p /tmp/.ssh
                                    chmod 700 /tmp/.ssh
                                    ssh-keyscan -p 7999 -H bitbucket.dada-tuda.ru > /tmp/.ssh/known_hosts
                                    chmod 644 /tmp/.ssh/known_hosts
                                '''

                                def gitSshCmd = "ssh -i ${SSH_KEY} -p 7999 -o IdentitiesOnly=yes -o UserKnownHostsFile=/tmp/.ssh/known_hosts -o StrictHostKeyChecking=yes"

                                withEnv([
                                        "PATH=/tools:${env.PATH}",
                                        "GIT_SSH_COMMAND=${gitSshCmd}"
                                ]) {
                                    sh """
                                        set -eux
                                        echo "\${DOCKER_LOGIN}" | docker login nexus.dada-tuda.ru -u \${DOCKER_USERNAME} --password-stdin
                                        docker push ${image}
                                        docker rmi ${image} || true
                                    """

                                    deployToArgo(
                                            'ssh://git@bitbucket.dada-tuda.ru:7999/dada/dada-argo.git',
                                            values,
                                            result.getTag(),
                                            commitAuthor,
                                            commitMessage,
                                            'develop'
                                    )
                                }
                            }
                        }
                    }
                } else {
                    echo "Docker deploy skipped"
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

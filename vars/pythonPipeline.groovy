def call(Map config) {
    properties([disableConcurrentBuilds(abortPrevious: true)])
    def commitAuthor
    def commitMessage

    def PROJECT_NAME = config.projectName
    def PYTHON_VER = config.get('python', '3.12')
    def DEPLOY_MODE = config.get('deploy', 'image')
    def deploy_build = DEPLOY_MODE == "image"
    def deploy_package = DEPLOY_MODE == "dist"

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
                set -eux

        
                # Список всех нужных пакетов
                DEPS="$python $python-venv $python-dev build-essential libpq-dev software-properties-common"
        
                MISSING=""
        
                # Проверяем, какие пакеты не установлены
                for pkg in \$DEPS; do
                    if ! dpkg -s \$pkg >/dev/null 2>&1; then
                        echo "Package \$pkg is missing"
                        MISSING="\$MISSING \$pkg"
                    else
                        echo "Package \$pkg already installed"
                    fi
                done
        
                if [ -n "\$MISSING" ]; then
                    echo "Installing missing packages:\$MISSING"
        
                    apt-get update
        
                    # PPA нужен только для нестандартных версий Python
                    add-apt-repository -y ppa:deadsnakes/ppa || true
                    apt-get update
        
                    apt-get install -y \$MISSING
                else
                    echo "All required packages already installed"
                fi
        
                # Дополни   тельная проверка, что бинарь действительно есть
                if ! command -v $python >/dev/null 2>&1; then
                    echo "ERROR: $python package is installed but binary not found in PATH"
                    which $python || true
                    exit 1
                fi
            """
        }


        if (fileExists("requirements.txt")) {
            stage('Install deps - pip') {
                withCredentials([file(credentialsId: 'pip-nexus-settings', variable: 'PIP_SETTINGS')]) {
                    sh """
                    $python -m venv venv
                    . venv/bin/activate
                    python -m ensurepip
                    export PIP_CONFIG_FILE='${PIP_SETTINGS}'
                    python -m pip install -r requirements.txt
                    
                    if [ -f requirements-dev.txt ]; then
                        python -m pip install -r requirements-dev.txt
                    fi
                """
                }
            }
        } else {
            echo "no requirements.txt - skip"
        }

        if (fileExists("pyproject.toml")) {
            stage('Install deps - uv') {
                withCredentials([file(credentialsId: 'uv-nexus-settings', variable: 'UV_SETTINGS')]) {
                    sh """
                        curl -LsSf https://astral.sh/uv/install.sh | sh
                        export PATH="$HOME/.local/bin:$PATH"
                        sed -i "s/^requires-python *= *\\"==[0-9.]*\\"/requires-python = \\"==${PYTHON_VER}\\"/" pyproject.toml
                        uv sync -v --config-file=${env.UV_SETTINGS}
                        cp -f ${env.UV_SETTINGS} ./uv.toml
                    """
                }
            }
        } else {
            echo "no pyproject.toml - skip"
        }

        stage("Test & Lint") {
            sh """
                set -eux
                # Активируем виртуальное окружение: сначала .venv от uv, потом старый venv
                if [ -f ".venv/bin/activate" ]; then
                    . .venv/bin/activate
                elif [ -f "venv/bin/activate" ]; then
                    . venv/bin/activate
                else
                    echo "⚠️  No virtualenv (.venv or venv) found — cannot run tests"
                    exit 1
                fi
        
                # Добавляем проект в PYTHONPATH (до pytest)
                export PYTHONPATH="\$PWD:\${PYTHONPATH:-}"
        
                echo "Checking for tests..."
        
                # Ищем директории tests/ или test/
                TEST_DIRS=\$(find . -maxdepth 2 -type d \\( -name "tests" -o -name "test" \\))
        
                # Ищем любые тестовые файлы test_*.py или *_test.py
                TEST_FILES=\$(find . -maxdepth 3 -type f \\( -name "test_*.py" -o -name "*_test.py" \\))
        
                if [ -z "\$TEST_DIRS" ] && [ -z "\$TEST_FILES" ]; then
                    echo "⚠️  No tests found — skipping pytest"
                else
                    echo "✓ Tests detected — running pytest"
                    python -m pip install pytest pytest-cov pytest-asyncio testcontainers
                    python -m pytest
                fi
            """

            if (!isPR() && env.BRANCH_NAME == 'develop') {
                def scannerHome = tool 'SonarScanner'


                if (!fileExists('sonar-project.properties')) {
                    echo "⚠️  sonar-project.properties not found — skipping Sonar scan"
                } else {
                    def props = readFile('sonar-project.properties')
                    def missing = []

                    // список обязательных параметров
                    ['sonar.projectKey', 'sonar.sources'].each { key ->
                        if (!props.contains("${key}=")) {
                            missing << key
                        }
                    }

                    if (!missing.isEmpty()) {
                        echo "⚠️  Missing mandatory Sonar properties: ${missing.join(', ')} — skipping Sonar scan"
                    } else {
                        withSonarQubeEnv() {
                            sh "${scannerHome}/bin/sonar-scanner"
                        }
                    }
                }
            }
        }

        if (!isPR() && deploy_build && (env.BRANCH_NAME == 'develop' || env.BRANCH_NAME == 'master')) {
            stage('Docker Deploy && Deploy to ArgoCD') {
                def values
                if (env.BRANCH_NAME == 'master') {
                    values = "helm/python/services/${PROJECT_NAME}/values-prod.yaml"
                } else {
                    values = "helm/python/services/${PROJECT_NAME}/values-dev.yaml"
                }
                def result = makeDockerImageInfo(PROJECT_NAME, 'dada', '1.0.0')
                def image = result.getName() + ':' + result.getTag()
                sh """
                    docker build --build-arg PYTHON_VERSION=${PYTHON_VER} -t ${image} .
                """
                doDeploy(image)
                deployToArgo('ssh://git@bitbucket.dada-tuda.ru:7999/dada/dada-argo.git',
                        values,
                        result.getTag(),
                        commitAuthor,
                        commitMessage,
                        'develop')
            }

        }
        if (deploy_package) {
            stage("Build Python") {
                script {
                    sh """
                        set -x
                        uv pip install  build
                        python -m build
                    """
                }
            }
            if (!isPR()) {
                stage("PythonDeploy") {
                    sh """
                        twine upload -r nexus dist/* --config-file .pypirc
                    """
                }
            }
        }
    }

}

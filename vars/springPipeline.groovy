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
    def podLabel = "kubeagent-${env.JOB_BASE_NAME ?: 'job'}-${env.BUILD_NUMBER ?: 'manual'}"
            .replaceAll('[^A-Za-z0-9-]', '-')
            .toLowerCase()
    def agentName = "kubeagent-${env.JOB_BASE_NAME}-${env.BUILD_NUMBER}-${UUID.randomUUID().toString().take(6)}"

    podTemplate(
            cloud: 'self-managed',
            label: podLabel,
            namespace: 'devops-tools',
            serviceAccount: 'jenkins-admin',
            yaml: """
apiVersion: v1
kind: Pod
metadata:
  name: ${agentName}
  annotations:
    argocd.argoproj.io/tracking-id: "jenkins-beget:/Pod:devops-tools/${agentName}"
  labels:
    app.kubernetes.io/name: jenkins-agent
    app.kubernetes.io/part-of: jenkins-common
    app.kubernetes.io/managed-by: jenkins
spec:
  priorityClassName: critical
  securityContext:
    fsGroup: 1000
  volumes:
    - name: workspace-volume
      emptyDir:
        sizeLimit: 3Gi
    - name: m2-cache
      emptyDir:
        sizeLimit: 2Gi
    - name: tools-volume
      emptyDir: {}
  containers:
    - name: jnlp
      image: jenkins/inbound-agent:latest
      tty: true
      workingDir: /home/jenkins/agent
      resources:
        requests:
          cpu: "50m"
          memory: "128Mi"
        limits:
          cpu: "300m"
          memory: "256Mi"
      volumeMounts:
        - name: workspace-volume
          mountPath: /home/jenkins/agent
        - name: tools-volume
          mountPath: /tools

    - name: builder
      image: ${BUILDER_IMAGE}
      command: ['cat']
      tty: true
      workingDir: /home/jenkins/agent
      env:
        - name: HOME
          value: /tmp
        - name: MAVEN_OPTS
          value: -Dmaven.repo.local=/root/.m2/repository/
        - name: PATH
          value: /usr/share/maven/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
      resources:
        requests:
          cpu: "250m"
          memory: "512Mi"
        limits:
          cpu: "1500m"
          memory: "1536Mi"
      volumeMounts:
        - name: workspace-volume
          mountPath: /home/jenkins/agent
        - name: m2-cache
          mountPath: /root/.m2/repository
        - name: tools-volume
          mountPath: /tools
"""
    ) {
        node(podLabel) {
            cleanWs()

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
}

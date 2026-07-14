/**
 * Build a Docker image with BuildKit registry cache and push it, in one step.
 *
 * Runs a buildx `docker-container` builder against the dind sidecar so it can
 * export a full (mode=max) layer + RUN-cache-mount cache to the registry. The
 * registry is Nexus, whose blob store is S3-backed, so the cache is offloaded
 * to S3 without any extra plumbing. Next build imports that cache
 * (`--cache-from`) and reuses layers + package caches across pods, instead of
 * the previous emptyDir graph store that was discarded when the agent pod died.
 *
 * Must run inside `container('docker')`. Logs `BUILD_SECONDS=<n>` for the pure
 * build+push span so build duration can be measured from the console log.
 *
 * @param args.image        full image ref to push, e.g. nexus.../app:tag  (required)
 * @param args.cacheRef     registry ref for the cache, e.g. nexus.../app:buildcache-<branch> (required)
 * @param args.dockerfile   path to Dockerfile (default 'Dockerfile')
 * @param args.context      build context dir (default '.')
 * @param args.buildArgs    list of "KEY=VALUE" build args (default [])
 * @param args.push         true → buildx --push (build+push in one). false → --load
 *                          the image into the local docker so a later step pushes
 *                          it (e.g. pythonPipeline's doDeploy). Default true.
 * @param args.registry     registry host to log in to (default 'nexus.dada-tuda.ru')
 * @param args.credentialsId  usernamePassword creds id (default 'docker-nexus-admin-psws')
 */
def call(Map args) {
    String image      = args.image ?: error('dockerBuildxPush: image is required')
    String cacheRef   = args.cacheRef ?: error('dockerBuildxPush: cacheRef is required')
    String dockerfile = (args.get('dockerfile', 'Dockerfile') ?: 'Dockerfile') as String
    String context    = (args.get('context', '.') ?: '.') as String
    String registry   = (args.get('registry', 'nexus.dada-tuda.ru') ?: 'nexus.dada-tuda.ru') as String
    String credId     = (args.get('credentialsId', 'docker-nexus-admin-psws') ?: 'docker-nexus-admin-psws') as String
    List   buildArgs  = (args.get('buildArgs', []) ?: []) as List
    boolean push      = (args.get('push', true)) as boolean
    String metaFile   = (args.get('metadataFile', '') ?: '') as String

    String baFlags = buildArgs.collect { "--build-arg ${it}" }.join(' ')
    String outputFlag = push ? '--push' : '--load'
    String metaFlag = metaFile ? "--metadata-file ${metaFile}" : ''

    String buildxCmd = [
            'docker buildx build',
            baFlags,
            "--cache-from type=registry,ref=${cacheRef}",
            "--cache-to type=registry,ref=${cacheRef},mode=max,image-manifest=true,oci-mediatypes=true,ignore-error=true",
            "-f ${dockerfile}",
            "-t ${image}",
            metaFlag,
            "${outputFlag} ${context}",
    ].findAll { it }.join(' ')

    withCredentials([usernamePassword(credentialsId: credId, usernameVariable: 'DOCKER_USERNAME', passwordVariable: 'DOCKER_LOGIN')]) {
        sh """
            set -eux
            docker version
            echo "\${DOCKER_LOGIN}" | docker login ${registry} -u "\${DOCKER_USERNAME}" --password-stdin

            docker buildx inspect jenkins >/dev/null 2>&1 || \\
                docker buildx create --name jenkins --driver docker-container --driver-opt network=host --use
            docker buildx use jenkins

            T0=\$(date +%s)
            attempt=1; max=3
            until ${buildxCmd}; do
                if [ \$attempt -ge \$max ]; then
                    echo "dockerBuildxPush: build+push failed after \$attempt attempts" >&2
                    exit 1
                fi
                echo "[push-retry \$attempt/\$((max-1))] buildx build+push failed (often a transient nexus registry 'digest mismatch' on a large layer); retrying in 10s..." >&2
                attempt=\$((attempt+1)); sleep 10
            done
            echo "BUILD_SECONDS=\$(( \$(date +%s) - T0 ))"
        """
    }
}

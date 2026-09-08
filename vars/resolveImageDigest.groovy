/**
 * Resolve the immutable manifest digest of an image that is already in the
 * registry, so a release can be pinned by content instead of by tag.
 *
 * Why this exists: the pipelines mint a mutable tag (`master-1.0.0-<build>`)
 * and both deploy tracks -- the Argo values bump and the Dada Console deploy --
 * carried that tag verbatim. Jenkins build counters are per-job state and get
 * reset; when that happened for profi-backend, `master-1.0.0-6` named an August
 * image on the fin-data.pro VM and a September image in Nexus. The VM had the
 * old one cached, `imagePullPolicy: IfNotPresent` and compose's pull-if-absent
 * both refused to fetch, git said the stack was current, and prod ran code 104
 * builds behind until an alembic revision it had never seen crash-looped it for
 * 22 hours. A digest cannot be reused, so the same class of drift cannot recur.
 *
 * `docker buildx imagetools inspect` asks the REGISTRY, not the local graph
 * store: the answer is the manifest that a `docker pull` of that tag would
 * fetch right now, which is exactly what has to be pinned. It is queried after
 * the push, so a failed or partial push surfaces here rather than being pinned.
 * For a multi-arch tag `.Manifest` is the index descriptor, so the pinned
 * digest stays the multi-platform one and does not lock the deploy to an arch.
 *
 * The digest is read out of `--format '{{json .Manifest}}'` rather than the
 * shorter `{{.Manifest.Digest}}`: buildx v0.31 silently ignores the latter and
 * prints its default human block instead of the field, so a pipeline built on
 * it would pin the string "Name: ...\nMediaType: ...". The sha256 shape check
 * below is the backstop for any future template that degrades the same way.
 *
 * Must run inside `container('docker')`, after the image has been pushed.
 *
 * @param args.image          full pushed image ref, e.g. nexus.../app:master-1.0.0-7 (required)
 * @param args.registry       registry host to log in to (default 'nexus.dada-tuda.ru')
 * @param args.credentialsId  usernamePassword creds id (default 'docker-nexus-admin-psws')
 * @return the manifest digest, e.g. 'sha256:8f1c...'
 */
def call(Map args) {
    String image    = args.image ?: error('resolveImageDigest: image is required')
    String registry = (args.get('registry', 'nexus.dada-tuda.ru') ?: 'nexus.dada-tuda.ru') as String
    String credId   = (args.get('credentialsId', 'docker-nexus-admin-psws') ?: 'docker-nexus-admin-psws') as String

    String digest
    withCredentials([usernamePassword(credentialsId: credId, usernameVariable: 'DOCKER_USERNAME', passwordVariable: 'DOCKER_LOGIN')]) {
        digest = sh(returnStdout: true, script: """
            set -eu
            set +x
            echo "\${DOCKER_LOGIN}" | docker login ${registry} -u "\${DOCKER_USERNAME}" --password-stdin >/dev/null

            n=0
            until out=\$(docker buildx imagetools inspect --format '{{json .Manifest}}' ${image} 2>&1); do
                n=\$((n + 1))
                if [ \$n -ge 5 ]; then
                    echo "resolveImageDigest: registry did not answer for ${image} after \$n attempts: \$out" >&2
                    exit 1
                fi
                sleep 5
            done
            printf '%s' "\$out" | sed -n 's/.*"digest"[[:space:]]*:[[:space:]]*"\\(sha256:[a-f0-9]*\\)".*/\\1/p' | head -n 1
        """).trim()
    }

    if (!(digest ==~ /^sha256:[a-f0-9]{64}$/)) {
        error("resolveImageDigest: registry returned '${digest}' for ${image}, which is not a sha256 manifest digest")
    }

    echo "resolveImageDigest: ${image} -> ${digest}"
    return digest
}

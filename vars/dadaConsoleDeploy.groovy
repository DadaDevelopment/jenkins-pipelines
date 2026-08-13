/**
 * Release an already-pushed image through the Dada Cloud console instead of by
 * writing a tag into the gitops repo.
 *
 * The console owns both deploy tracks. `POST /api/v1/deploy` enqueues the same
 * DeployImageVersion operation for a Kubernetes app and for a VM (compose) app;
 * the difference is resolved inside the platform, not here. So a pipeline that
 * releases through this step carries no Portainer specifics, no stack ids and no
 * knowledge of whether its target is a cluster or a machine.
 *
 * It also closes the gap that let the console's model of a VM rot: a release
 * that goes through the API updates the resource snapshot, whereas a release
 * that edits the gitops compose by hand leaves the console believing whatever it
 * last saw. fin-core/findata drifted 104 backend builds that way.
 *
 * The calls run in the `jnlp` container on purpose. Pipelines reach this step from
 * inside a toolchain container, and those images carry no HTTP client: the
 * `docker:24-cli` image has only busybox wget and the uv builder image has
 * neither, so a release died with `curl: not found` after the image was already
 * pushed. `jenkins/inbound-agent` ships curl and exists in every pod template
 * here, and the workspace is the same volume in every container.
 *
 * The token is a per-app deploy hook (minted at
 * POST /api/v1/projects/{p}/environments/{e}/apps/{a}/deploy-hooks) held as a
 * Jenkins secret-text credential. It is scoped to one app: it cannot deploy
 * anything else and cannot read another app's operations.
 *
 * Fails the build when the operation ends non-OK or does not reach a terminal
 * state within timeoutSeconds. A deploy whose outcome is unknown is a failure,
 * not a pass -- the whole point of moving off the hand-edit is that the pipeline
 * learns whether prod actually took the image.
 *
 * @param args.image               full image ref to release, e.g. nexus.../profi-backend:master-1.0.0-523 (required)
 * @param args.tokenCredentialsId  Jenkins secret-text credential holding the deploy-hook token (required)
 * @param args.consoleUrl          console base URL (default 'https://console.dada-tuda.ru')
 * @param args.timeoutSeconds      how long to wait for a terminal operation (default 900)
 * @param args.pollSeconds         delay between polls (default 10)
 * @param args.container           pod container to run the HTTP calls in (default 'jnlp')
 */
def call(Map args) {
    String image    = args.image ?: error('dadaConsoleDeploy: image is required')
    String credId   = args.tokenCredentialsId ?: error('dadaConsoleDeploy: tokenCredentialsId is required')
    String console  = (args.get('consoleUrl', 'https://console.dada-tuda.ru') ?: 'https://console.dada-tuda.ru') as String
    int    waitSecs = (args.get('timeoutSeconds', 900) ?: 900) as int
    int    pollSecs = (args.get('pollSeconds', 10) ?: 10) as int
    String runIn    = (args.get('container', 'jnlp') ?: 'jnlp') as String

    console = console.replaceAll('/+$', '')

    withCredentials([string(credentialsId: credId, variable: 'DADA_DEPLOY_TOKEN')]) {
        withEnv([
                "DADA_CONSOLE_URL=${console}",
                "DADA_DEPLOY_IMAGE=${image}",
                "DADA_DEPLOY_WAIT=${waitSecs}",
                "DADA_DEPLOY_POLL=${pollSecs}",
        ]) {
            container(runIn) {
                sh '''
                set -eu
                set +x

                work=$(mktemp -d)
                trap 'rm -rf "$work"' EXIT
                field() { sed -n "s/.*\\"$2\\"[[:space:]]*:[[:space:]]*\\"\\{0,1\\}\\([^\\",}]*\\).*/\\1/p" "$1" | head -n 1; }

                printf '{"image":"%s"}' "$DADA_DEPLOY_IMAGE" > "$work/req.json"
                code=$(curl -sS -o "$work/trigger.json" -w '%{http_code}' -X POST \
                    -H "X-Dada-Deploy-Token: $DADA_DEPLOY_TOKEN" \
                    -H "Content-Type: application/json" \
                    --data-binary @"$work/req.json" \
                    "$DADA_CONSOLE_URL/api/v1/deploy")
                if [ "$code" != "200" ] && [ "$code" != "202" ]; then
                    echo "dadaConsoleDeploy: POST /api/v1/deploy returned HTTP $code: $(cat "$work/trigger.json")" >&2
                    exit 1
                fi

                op=$(field "$work/trigger.json" operation_id)
                if [ -z "$op" ]; then
                    echo "dadaConsoleDeploy: no operation_id in response: $(cat "$work/trigger.json")" >&2
                    exit 1
                fi
                echo "dadaConsoleDeploy: $DADA_DEPLOY_IMAGE -> operation $op"

                deadline=$(( $(date +%s) + DADA_DEPLOY_WAIT ))
                while :; do
                    code=$(curl -sS -o "$work/op.json" -w '%{http_code}' \
                        -H "X-Dada-Deploy-Token: $DADA_DEPLOY_TOKEN" \
                        "$DADA_CONSOLE_URL/api/v1/deploy/operations/$op")
                    if [ "$code" != "200" ]; then
                        echo "dadaConsoleDeploy: poll of operation $op returned HTTP $code: $(cat "$work/op.json")" >&2
                        exit 1
                    fi

                    state=$(field "$work/op.json" status)
                    terminal=$(field "$work/op.json" terminal)
                    ok=$(field "$work/op.json" ok)

                    if [ "$terminal" = "true" ]; then
                        if [ "$ok" = "true" ]; then
                            echo "dadaConsoleDeploy: operation $op finished $state"
                            exit 0
                        fi
                        echo "dadaConsoleDeploy: operation $op finished $state: $(cat "$work/op.json")" >&2
                        exit 1
                    fi

                    if [ "$(date +%s)" -ge "$deadline" ]; then
                        echo "dadaConsoleDeploy: operation $op still $state after ${DADA_DEPLOY_WAIT}s; outcome unknown" >&2
                        exit 1
                    fi

                    sleep "$DADA_DEPLOY_POLL"
                done
            '''
            }
        }
    }
}

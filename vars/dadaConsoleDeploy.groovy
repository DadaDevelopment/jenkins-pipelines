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
 * The HTTP client is whatever the current container has. Toolchain images differ:
 * `docker:24-cli` carries only busybox wget and the uv builder image carries
 * neither, which killed a release with `curl: not found` after the image was
 * already pushed. Switching into the `jnlp` container is NOT the fix -- a step
 * running there fights the agent process itself and the pod died mid-poll. So the
 * script probes for curl, falls back to busybox wget, and fails loudly when the
 * container has no client at all. Its `http` helper takes a body file, a URL and
 * an optional request-body file (which makes it a POST) and echoes the HTTP
 * status; under wget the status is read back out of the `-S` header dump, since
 * busybox wget writes no body and exits non-zero on 4xx/5xx.
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
 */
def call(Map args) {
    String image    = args.image ?: error('dadaConsoleDeploy: image is required')
    String credId   = args.tokenCredentialsId ?: error('dadaConsoleDeploy: tokenCredentialsId is required')
    String console  = (args.get('consoleUrl', 'https://console.dada-tuda.ru') ?: 'https://console.dada-tuda.ru') as String
    int    waitSecs = (args.get('timeoutSeconds', 900) ?: 900) as int
    int    pollSecs = (args.get('pollSeconds', 10) ?: 10) as int

    console = console.replaceAll('/+$', '')

    withCredentials([string(credentialsId: credId, variable: 'DADA_DEPLOY_TOKEN')]) {
        withEnv([
                "DADA_CONSOLE_URL=${console}",
                "DADA_DEPLOY_IMAGE=${image}",
                "DADA_DEPLOY_WAIT=${waitSecs}",
                "DADA_DEPLOY_POLL=${pollSecs}",
        ]) {
            sh '''
                set -eu
                set +x

                work=$(mktemp -d)
                trap 'rm -rf "$work"' EXIT
                field() { sed -n "s/.*\\"$2\\"[[:space:]]*:[[:space:]]*\\"\\{0,1\\}\\([^\\",}]*\\).*/\\1/p" "$1" | head -n 1; }

                if command -v curl >/dev/null 2>&1; then
                    client=curl
                elif command -v wget >/dev/null 2>&1; then
                    client=wget
                else
                    echo "dadaConsoleDeploy: neither curl nor wget in this container" >&2
                    exit 1
                fi

                http() {
                    if [ "$client" = curl ]; then
                        if [ $# -eq 3 ]; then
                            curl -sS -o "$1" -w '%{http_code}' -X POST \
                                -H "X-Dada-Deploy-Token: $DADA_DEPLOY_TOKEN" \
                                -H "Content-Type: application/json" \
                                --data-binary @"$3" "$2"
                        else
                            curl -sS -o "$1" -w '%{http_code}' \
                                -H "X-Dada-Deploy-Token: $DADA_DEPLOY_TOKEN" "$2"
                        fi
                        return 0
                    fi

                    if [ $# -eq 3 ]; then
                        wget -q -S -O "$1" \
                            --header="X-Dada-Deploy-Token: $DADA_DEPLOY_TOKEN" \
                            --header="Content-Type: application/json" \
                            --post-file "$3" "$2" 2> "$work/headers" || true
                    else
                        wget -q -S -O "$1" \
                            --header="X-Dada-Deploy-Token: $DADA_DEPLOY_TOKEN" \
                            "$2" 2> "$work/headers" || true
                    fi
                    sed -n 's|^[[:space:]]*HTTP/[0-9.]*[[:space:]]\\([0-9][0-9][0-9]\\).*|\\1|p' "$work/headers" | tail -n 1
                }

                printf '{"image":"%s"}' "$DADA_DEPLOY_IMAGE" > "$work/req.json"
                run_once() {
                    attempt=1
                    while :; do
                        code=$(http "$work/trigger.json" "$DADA_CONSOLE_URL/api/v1/deploy" "$work/req.json") || code=""
                        if [ "$code" = "200" ] || [ "$code" = "202" ]; then
                            break
                        fi
                        case "${code:-none}" in
                            000|none|502|503) transient=1 ;;
                            *) transient=0 ;;
                        esac
                        if [ "$transient" -eq 1 ] && [ "$attempt" -lt 15 ]; then
                            echo "dadaConsoleDeploy: POST /api/v1/deploy returned HTTP ${code:-none}, console unavailable, retry $attempt/14 in 20s" >&2
                            attempt=$((attempt + 1))
                            sleep 20
                            continue
                        fi
                        echo "dadaConsoleDeploy: POST /api/v1/deploy returned HTTP ${code:-none}: $(cat "$work/trigger.json" 2>/dev/null)" >&2
                        return 1
                    done

                    op=$(field "$work/trigger.json" operation_id)
                    if [ -z "$op" ]; then
                        echo "dadaConsoleDeploy: no operation_id in response: $(cat "$work/trigger.json")" >&2
                        return 1
                    fi
                    echo "dadaConsoleDeploy: $DADA_DEPLOY_IMAGE -> operation $op"

                    deadline=$(( $(date +%s) + DADA_DEPLOY_WAIT ))
                    polls_failed=0
                    while :; do
                        code=$(http "$work/op.json" "$DADA_CONSOLE_URL/api/v1/deploy/operations/$op") || code=""
                        if [ "$code" != "200" ]; then
                            case "${code:-none}" in
                                000|none|502|503|504) transient=1 ;;
                                *) transient=0 ;;
                            esac
                            polls_failed=$((polls_failed + 1))
                            if [ "$transient" -eq 1 ] && [ "$polls_failed" -lt 30 ] && [ "$(date +%s)" -lt "$deadline" ]; then
                                echo "dadaConsoleDeploy: poll of operation $op returned HTTP ${code:-none}, console unavailable, retrying" >&2
                                sleep "$DADA_DEPLOY_POLL"
                                continue
                            fi
                            echo "dadaConsoleDeploy: poll of operation $op returned HTTP ${code:-none}: $(cat "$work/op.json" 2>/dev/null)" >&2
                            return 1
                        fi
                        polls_failed=0

                        state=$(field "$work/op.json" status)
                        terminal=$(field "$work/op.json" terminal)
                        ok=$(field "$work/op.json" ok)

                        if [ "$terminal" = "true" ]; then
                            if [ "$ok" = "true" ]; then
                                echo "dadaConsoleDeploy: operation $op finished $state"
                                return 0
                            fi
                            echo "dadaConsoleDeploy: operation $op finished $state: $(cat "$work/op.json")" >&2
                            if grep -q -E 'portainer did not finish redeploying|failed to resolve reference|i/o timeout' "$work/op.json" 2>/dev/null; then
                                return 2
                            fi
                            return 1
                        fi

                        if [ "$(date +%s)" -ge "$deadline" ]; then
                            echo "dadaConsoleDeploy: operation $op still $state after ${DADA_DEPLOY_WAIT}s; outcome unknown" >&2
                            return 1
                        fi

                        sleep "$DADA_DEPLOY_POLL"
                    done
                }

                tries=1
                while :; do
                    run_once && rc=0 || rc=$?
                    if [ "$rc" -eq 0 ]; then
                        exit 0
                    fi
                    if [ "$rc" -eq 2 ] && [ "$tries" -lt 2 ]; then
                        tries=$((tries + 1))
                        echo "dadaConsoleDeploy: the VM stack redeploy failed on a registry pull or timeout, triggering the deploy again (attempt $tries/2)" >&2
                        sleep 15
                        continue
                    fi
                    exit 1
                done
            '''
        }
    }
}

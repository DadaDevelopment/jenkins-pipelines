import groovy.json.JsonOutput

def call(Map args){
    def webhookUrl = ((args.webhookUrl ?: '') as String).trim()
    if (!webhookUrl) {
        echo "ci failure webhook disabled for ${args.projectName}"
        return
    }

    def repoUrl = ((args.repoUrl ?: '') as String).trim()
    def branch = ((args.branch ?: '') as String).trim()
    def commitSha = ((args.commitSha ?: '') as String).trim()
    def jobName = ((args.jobName ?: '') as String).trim()
    def buildNumber = ((args.buildNumber ?: '') as String).trim()
    def failedStage = (((args.failedStage ?: 'unknown') as String).trim()) ?: 'unknown'
    if (!repoUrl || !branch || !commitSha || !jobName || !buildNumber) {
        echo "ci failure webhook skipped because required Jenkins/git metadata is missing"
        return
    }

    def payload = [
            repo_url       : repoUrl,
            branch         : branch,
            commit_sha     : commitSha,
            job_name       : jobName,
            build_number   : buildNumber,
            build_url      : ((args.buildUrl ?: '') as String).trim(),
            failed_stage   : failedStage,
            failure_summary: (((args.failureSummary ?: '') as String).trim()) ?: 'jenkins pipeline failed',
            trace_id       : (((args.traceId ?: '') as String).trim()) ?: "${jobName}#${buildNumber}",
            idempotency_key: "${jobName}:${buildNumber}:${failedStage}",
    ]

    def payloadFile = '.ci-failed-payload.json'
    writeFile file: payloadFile, text: JsonOutput.prettyPrint(JsonOutput.toJson(payload))

    try {
        def tokenCredentialId = ((args.webhookTokenCredentialId ?: '') as String).trim()
        def authScheme = (((args.webhookAuthScheme ?: 'Bearer') as String).trim()) ?: 'Bearer'
        if (tokenCredentialId) {
            withEnv([
                    "CI_FAILURE_WEBHOOK_URL=${webhookUrl}",
                    "CI_FAILURE_WEBHOOK_AUTH_SCHEME=${authScheme}",
            ]) {
                try {
                    withCredentials([string(credentialsId: tokenCredentialId, variable: 'CI_FAILURE_WEBHOOK_TOKEN')]) {
                        _postCiFailurePayload(true)
                    }
                } catch (bindErr) {
                    if (!"${bindErr}".contains('CredentialNotFoundException')) {
                        throw bindErr
                    }
                    echo "ci failure webhook token ${tokenCredentialId} is not secret text, reading its password half instead"
                    withCredentials([usernamePassword(
                            credentialsId: tokenCredentialId,
                            usernameVariable: 'CI_FAILURE_WEBHOOK_TOKEN_USER',
                            passwordVariable: 'CI_FAILURE_WEBHOOK_TOKEN',
                    )]) {
                        _postCiFailurePayload(true)
                    }
                }
            }
        } else {
            withEnv(["CI_FAILURE_WEBHOOK_URL=${webhookUrl}"]) {
                _postCiFailurePayload(false)
            }
        }
        echo "ci failure webhook delivered to ${webhookUrl}"
    } catch (hookErr) {
        echo "ci failure webhook failed: ${hookErr}"
    } finally {
        sh "rm -f ${payloadFile}"
    }
}

def _postCiFailurePayload(boolean authenticated) {
    if (authenticated) {
        sh '''
            set +x
            curl --silent --show-error --fail \
              --header "Content-Type: application/json" \
              --header "Authorization: ${CI_FAILURE_WEBHOOK_AUTH_SCHEME} ${CI_FAILURE_WEBHOOK_TOKEN}" \
              --data @.ci-failed-payload.json \
              "${CI_FAILURE_WEBHOOK_URL}" >/dev/null
        '''
        return
    }
    sh '''
        set +x
        curl --silent --show-error --fail \
          --header "Content-Type: application/json" \
          --data @.ci-failed-payload.json \
          "${CI_FAILURE_WEBHOOK_URL}" >/dev/null
    '''
}

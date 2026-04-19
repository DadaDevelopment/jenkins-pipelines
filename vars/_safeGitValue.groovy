def call(String cmd) {
    try {
        return sh(script: "${cmd} || true", returnStdout: true).trim()
    } catch (ignored) {
        return ''
    }
}
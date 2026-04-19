def call() {
    return ((env.CHANGE_BRANCH ?: env.BRANCH_NAME ?: '') as String).trim()
}
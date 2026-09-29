import ru.dada.build.RootDir

/**
 * Headless checks for the monorepo build directory. Run with:
 *   groovy -cp src test/RootDirTest.groovy
 */

int failures = 0
def check = { String name, boolean ok, String detail ->
    if (ok) {
        println "PASS ${name}"
    } else {
        failures++
        println "FAIL ${name}"
        println "  ${detail}"
    }
}

[null, '', ' ', '.', './', ' . '].each { raw ->
    String got = RootDir.normalize(raw)
    check("repo root ${raw == null ? 'null' : "'${raw}'"} stays at '.'", got == '.', "got ${got}")
}

[
        'agent-host'         : 'agent-host',
        './agent-host'       : 'agent-host',
        'agent-host/'        : 'agent-host',
        ' deploy/backup-tools': 'deploy/backup-tools',
        'a//b/./c'           : 'a/b/c',
        'v1.2_x-y'           : 'v1.2_x-y',
].each { raw, want ->
    String got = RootDir.normalize(raw)
    check("'${raw}' normalizes to '${want}'", got == want, "got ${got}")
}

['..', '../other', 'a/../../b', 'a/..', '/etc', '/', '//', 'a\\b', 'a b', "a'b", 'a;rm -rf', '$(id)', "a\nb", 'a`id`'].each { raw ->
    String got = RootDir.normalize(raw)
    check("'${raw.replace('\n', '\\n')}' is refused", got == null, "got ${got}")
}

if (failures) {
    println "${failures} check(s) failed"
    System.exit(1)
}
println 'all checks passed'

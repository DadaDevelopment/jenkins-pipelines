import ru.dada.build.GoSite

/**
 * Headless checks for the Go user-app Dockerfile. Run with:
 *   groovy -cp src test/GoSiteTest.groovy
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

[
        'no go.mod at all'                         : [null, '1.27'],
        'empty go.mod'                             : ['', '1.27'],
        'go line below the floor'                  : ['module x\n\ngo 1.22\n', '1.27'],
        'go line with patch below the floor'       : ['module x\n\ngo 1.25.0\n', '1.27'],
        'go line at the floor'                     : ['module x\n\ngo 1.27\n', '1.27'],
        'go line with patch at the floor'          : ['module x\n\ngo 1.27.3\n', '1.27'],
        'go line above the floor'                  : ['module x\n\ngo 1.28.1\n', '1.28'],
        'toolchain line wins over the go line'     : ['module x\n\ngo 1.25.0\ntoolchain go1.28.2\n', '1.28'],
        'toolchain below the floor is floored'     : ['module x\n\ngo 1.24\ntoolchain go1.25.4\n', '1.27'],
        'go line in a comment is ignored'          : ['module x\n// go 1.40\ngo 1.26\n', '1.27'],
        'a require line that mentions go is not it': ['module x\n\ngo 1.26\nrequire github.com/a/go 1.99.0\n', '1.27'],
        'release candidate go line'                : ['module x\n\ngo 1.28rc1\n', '1.28'],
        'garbage go line falls back to the floor'  : ['module x\n\ngo latest\n', '1.27'],
        'windows line endings'                     : ['module x\r\n\r\ngo 1.28.0\r\n', '1.28'],
].each { name, spec ->
    String got = GoSite.imageTag(spec[0])
    check(name, got == spec[1], "got ${got}, want ${spec[1]}")
}

String df = GoSite.dockerfile(0, 'module x\n\ngo 1.25.0\n')
check('uses the floored image', df.startsWith('FROM golang:1.27 AS build\n'), df.readLines().first())
check('defaults to port 8080', df.contains('EXPOSE 8080\n'), df)
check('honours an explicit port', GoSite.dockerfile(3000, null).contains('EXPOSE 3000\n'), 'no EXPOSE 3000')
check('builds a static binary', df.contains('CGO_ENABLED=0 go build -o /app/server .'), df)
check('heals a rejected go.sum', df.contains('SECURITY ERROR') && df.contains('rm -f go.sum') && df.contains('go mod tidy'), df)
check('keeps the exit code of the first build when the heal does not fix it', df.contains('exit $rc'), df)
check('runtime stage is unchanged', df.contains('FROM gcr.io/distroless/base-debian12\nCOPY --from=build /app/server /server\n'), df)
check('no stray groovy interpolation left', !df.contains('${') && !df.contains('@@'), df)

if (failures > 0) {
    println "${failures} FAILED"
    System.exit(1)
}
println 'ALL PASS'

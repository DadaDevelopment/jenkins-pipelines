package ru.dada.build

/**
 * Dockerfile template for Go user apps that ship no Dockerfile.
 *
 * Two things the old inline template got wrong:
 *
 * 1. The Go version was pinned to a release that kept ageing. A repo whose
 *    go.mod asked for a newer toolchain made Go download that toolchain at build
 *    time and the build died there. The image is now the newest line the
 *    platform tracks ({@link #MIN_GO}), or newer when go.mod's toolchain / go
 *    directive asks for it.
 * 2. A go.sum that does not match the module downloads (hand-edited, generated
 *    by an assistant, copied from another project) ends the build with
 *    "SECURITY ERROR ... checksum mismatch", which nothing can fix by editing
 *    source. The build now regenerates go.sum once and retries. That stays safe:
 *    `go mod tidy` verifies every fresh hash against the Go checksum database
 *    (GOSUMDB is left on), so a tampered module still fails.
 */
class GoSite {

    /** Newest Go line the platform builds with; go.mod can only raise it. */
    static final String MIN_GO = '1.27'

    static final int DEFAULT_PORT = 8080

    /**
     * Picks the golang image tag (major.minor) for a go.mod.
     *
     * @param goMod go.mod text, null or blank when the repo has none
     * @return MIN_GO, or the higher major.minor go.mod asks for
     */
    static String imageTag(String goMod) {
        String wanted = directive(goMod, 'toolchain', /go(\d+)\.(\d+)\S*/) ?: directive(goMod, 'go', /(\d+)\.(\d+)\S*/)
        return wanted && compare(wanted, MIN_GO) > 0 ? wanted : MIN_GO
    }

    /**
     * Renders the Dockerfile.
     *
     * @param port  app port, 0 means default
     * @param goMod go.mod text used to pick the Go image, may be null
     */
    static String dockerfile(int port, String goMod) {
        int p = port ?: DEFAULT_PORT
        List<String> lines = [
                'FROM golang:' + imageTag(goMod) + ' AS build',
                'WORKDIR /app',
                'COPY . .',
                'RUN set -u; \\',
                '    CGO_ENABLED=0 go build -o /app/server . >/tmp/go-build.log 2>&1; rc=$?; cat /tmp/go-build.log; \\',
                '    if [ $rc -ne 0 ] && grep -qE \'SECURITY ERROR|missing go.sum entry\' /tmp/go-build.log; then \\',
                '      echo "==> go.sum was rejected by the Go toolchain; regenerating it (hashes are re-verified against the Go checksum database)"; \\',
                '      rm -f go.sum && go mod tidy && CGO_ENABLED=0 go build -o /app/server . && rc=0; \\',
                '    fi; \\',
                '    exit $rc',
                'FROM gcr.io/distroless/base-debian12',
                'COPY --from=build /app/server /server',
                'EXPOSE ' + p,
                'CMD ["/server"]',
        ]
        return lines.join('\n') + '\n'
    }

    private static String directive(String goMod, String keyword, String valuePattern) {
        if (!goMod) {
            return null
        }
        for (String raw : goMod.split(/\r?\n/)) {
            def m = raw.trim() =~ ('^' + keyword + /\s+/ + valuePattern + '$')
            if (m.matches()) {
                return m.group(1) + '.' + m.group(2)
            }
        }
        return null
    }

    private static int compare(String a, String b) {
        List<Integer> x = a.split(/\./).collect { it as int }
        List<Integer> y = b.split(/\./).collect { it as int }
        return x[0] != y[0] ? x[0] <=> y[0] : x[1] <=> y[1]
    }
}

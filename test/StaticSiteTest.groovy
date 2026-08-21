import ru.dada.build.StaticSite

/**
 * Headless checks for the static / SPA Dockerfile template. Run with:
 *   groovy -cp src test/StaticSiteTest.groovy
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

String plainHtml = StaticSite.dockerfile(80, '', '', '.')

check('plain html repo never runs npm unguarded',
        !(plainHtml =~ /(?m)^RUN (npm|yarn|pnpm)/),
        "npm runs as its own unguarded step:\n${plainHtml}")

check('plain html repo guards the build on package.json',
        plainHtml.contains('if [ -f package.json ]; then'),
        "no package.json guard:\n${plainHtml}")

check('plain html repo still reaches nginx with its files',
        plainHtml.contains('mkdir -p /out && cp -r . /out') &&
                plainHtml.contains('COPY --from=build /out /usr/share/nginx/html'),
        "repo files never land in the nginx stage:\n${plainHtml}")

String vite = StaticSite.dockerfile(0, 'npm ci', 'npm run build', 'dist')

check('vite repo keeps install and build',
        vite.contains('npm ci && npm run build && cp -r "dist" /out'),
        "install/build chain changed:\n${vite}")

check('blank commands fall back to npm defaults',
        StaticSite.dockerfile(0, '', '', '').contains('npm install && npm run build && cp -r "dist" /out'),
        "defaults changed:\n${StaticSite.dockerfile(0, '', '', '')}")

check('port defaults to 80 and is exposed',
        vite.contains('EXPOSE 80') && vite.contains('listen 80;'),
        "port handling changed:\n${vite}")

check('explicit port wins',
        StaticSite.dockerfile(8080, '', '', '').contains('EXPOSE 8080'),
        'explicit port ignored')

check('spa fallback route survives',
        vite.contains('try_files \$uri \$uri/ /index.html;'),
        "nginx spa fallback lost:\n${vite}")

println failures == 0 ? "\nOK" : "\n${failures} FAILED"
System.exit(failures == 0 ? 0 : 1)

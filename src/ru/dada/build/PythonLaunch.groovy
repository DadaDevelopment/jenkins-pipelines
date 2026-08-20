package ru.dada.build

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Builds the runtime start line for the templated Python Dockerfile.
 *
 * The historical line only guessed a FILE name (main.py, bot.py, app.py, then
 * any *.py, at the root and one directory down) and ran it with plain python.
 * For the standard FastAPI layout, where the entrypoint lives inside a package
 * (app/main.py, src/main.py), that guess runs "python app/main.py", Python sets
 * sys.path[0] to /app/app, and the very first absolute import of the package
 * dies with ModuleNotFoundError. Live case 2026-08-20: gulyaev-ai-core spent
 * 16 hours in CrashLoopBackOff with 198 restarts after a build that had
 * detected fastapi correctly and had installed uvicorn.
 *
 * So when the framework really is FastAPI and the entrypoint is one directory
 * down, the launch line becomes a uvicorn invocation over the DOTTED module
 * path (app/main.py -> app.main), which keeps /app as the import root.
 *
 * Deliberate limits, so nothing that works today changes shape:
 *   - a root-level main.py / bot.py / app.py keeps the old python <file> path,
 *     because such a file usually calls uvicorn.run() itself;
 *   - a non-fastapi framework keeps the old path;
 *   - a candidate whose source never constructs an ASGI application keeps the
 *     old path, because the attribute name cannot be proven;
 *   - even the uvicorn line is guarded by "command -v uvicorn", falling back
 *     to the old guesser inside the same shell when uvicorn is not installed.
 */
class PythonLaunch {

    /** Frameworks served by the python Dockerfile template. */
    static final List<String> TEMPLATE_FRAMEWORKS = ['fastapi', 'django', 'flask', 'python'].asImmutable()

    /** Entry file names the legacy guesser tries at the repository root. */
    static final List<String> ROOT_ENTRY_NAMES = ['main.py', 'bot.py', 'app.py'].asImmutable()

    /** Entry file names looked for one directory down, in preference order. */
    static final List<String> NESTED_ENTRY_NAMES = ['main.py', 'app.py', 'asgi.py', 'server.py', 'api.py'].asImmutable()

    /** The legacy filename-guessing launch line, kept as the fallback. */
    static final String GUESS_LAUNCH_LINE = 'for f in main.py bot.py app.py; do [ -f "$f" ] && exec python "$f"; done; f=$(ls *.py 2>/dev/null | head -1); [ -n "$f" ] && exec python "$f"; for d in */; do for f in main.py bot.py app.py; do [ -f "$d$f" ] && exec python "$d$f"; done; done; f=$(ls */*.py 2>/dev/null | head -1); [ -n "$f" ] && exec python "$f"; echo "no python entrypoint found (looked for main.py, bot.py, app.py, then any *.py, at the root and one directory down)" >&2; exit 1'

    private static final Pattern ASGI_ASSIGNMENT =
            Pattern.compile('(?m)^[ \\t]*([A-Za-z_][A-Za-z0-9_]*)[ \\t]*=[ \\t]*(?:[A-Za-z_][A-Za-z0-9_.]*\\.)?(?:FastAPI|Starlette)[ \\t]*\\(')

    /**
     * Full start line for the generated Dockerfile CMD.
     *
     * @param framework framework the build-agent detected, empty when unknown
     * @param port      listen port, 0 when unknown
     * @param sources   candidate python file path (repo-relative, '/' separated) to its content
     * @param startCmd  start command carried by the build request; wins over everything
     */
    static String startLine(String framework, int port, Map<String, String> sources, String startCmd) {
        String explicit = (startCmd ?: '').trim()
        if (explicit) {
            return explicit
        }
        String asgi = asgiLaunchPrefix(framework, port, sources)
        return asgi ? asgi + GUESS_LAUNCH_LINE : GUESS_LAUNCH_LINE
    }

    /**
     * Guarded uvicorn invocation for a package-style FastAPI entrypoint, or
     * null when the old filename guess must be kept.
     */
    static String asgiLaunchPrefix(String framework, int port, Map<String, String> sources) {
        if (((framework ?: '').trim().toLowerCase()) != 'fastapi') {
            return null
        }
        if (!sources) {
            return null
        }
        for (String name : ROOT_ENTRY_NAMES) {
            if (sources.containsKey(name)) {
                return null
            }
        }
        int listenPort = port > 0 ? port : 8000
        for (String path : nestedCandidates(sources.keySet())) {
            String attribute = asgiAttribute(sources.get(path))
            if (!attribute) {
                continue
            }
            String module = moduleOf(path)
            return "if command -v uvicorn >/dev/null 2>&1; then exec uvicorn ${module}:${attribute} --host 0.0.0.0 --port ${listenPort}; fi; "
        }
        return null
    }

    /** Package-style entry files one directory down, most likely first. */
    static List<String> nestedCandidates(Collection<String> paths) {
        List<String> found = []
        for (String path : (paths ?: [])) {
            if (!path || !path.endsWith('.py')) {
                continue
            }
            if (path.count('/') != 1) {
                continue
            }
            if (NESTED_ENTRY_NAMES.contains(path.substring(path.indexOf('/') + 1))) {
                found << path
            }
        }
        found.sort { a, b ->
            int byName = NESTED_ENTRY_NAMES.indexOf(a.substring(a.indexOf('/') + 1)) <=>
                    NESTED_ENTRY_NAMES.indexOf(b.substring(b.indexOf('/') + 1))
            byName != 0 ? byName : (a.length() <=> b.length() ?: a <=> b)
        }
        return found
    }

    /** Name the module binds an ASGI application to, or null when it binds none. */
    static String asgiAttribute(String content) {
        if (!content) {
            return null
        }
        Matcher m = ASGI_ASSIGNMENT.matcher(content)
        return m.find() ? m.group(1) : null
    }

    /** Dotted import path of a repo-relative python file: app/main.py -> app.main. */
    static String moduleOf(String path) {
        return path.substring(0, path.length() - 3).replace('/', '.')
    }
}

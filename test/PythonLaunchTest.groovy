import ru.dada.build.PythonLaunch

/**
 * Headless checks for the python launch line. Run with:
 *   groovy -cp src test/PythonLaunchTest.groovy
 */

int failures = 0
def check = { String name, Object actual, Object expected ->
    if (actual == expected) {
        println "PASS ${name}"
    } else {
        failures++
        println "FAIL ${name}"
        println "  expected: ${expected}"
        println "  actual  : ${actual}"
    }
}

String fastapiModule = '''from fastapi import FastAPI
from app.core.config import settings

app = FastAPI(title="core")
'''

String scriptStyle = '''import uvicorn
from app import create_app

if __name__ == "__main__":
    uvicorn.run("app:create_app", host="0.0.0.0", port=8000)
'''

check('root main.py keeps the legacy guesser',
        PythonLaunch.startLine('fastapi', 8000, ['main.py': scriptStyle], ''),
        PythonLaunch.GUESS_LAUNCH_LINE)

check('root bot.py keeps the legacy guesser',
        PythonLaunch.asgiLaunchPrefix('fastapi', 8000, ['bot.py': scriptStyle, 'app/main.py': fastapiModule]),
        null)

check('app/main.py fastapi launches uvicorn on the dotted module',
        PythonLaunch.startLine('fastapi', 8000, ['app/main.py': fastapiModule], ''),
        'if command -v uvicorn >/dev/null 2>&1; then exec uvicorn app.main:app --host 0.0.0.0 --port 8000; fi; ' + PythonLaunch.GUESS_LAUNCH_LINE)

check('src/main.py fastapi launches uvicorn on the dotted module',
        PythonLaunch.startLine('fastapi', 9000, ['src/main.py': fastapiModule], ''),
        'if command -v uvicorn >/dev/null 2>&1; then exec uvicorn src.main:app --host 0.0.0.0 --port 9000; fi; ' + PythonLaunch.GUESS_LAUNCH_LINE)

check('a non-default application attribute is carried through',
        PythonLaunch.asgiLaunchPrefix('fastapi', 8000, ['app/main.py': 'import fastapi\napi = fastapi.FastAPI()\n']),
        'if command -v uvicorn >/dev/null 2>&1; then exec uvicorn app.main:api --host 0.0.0.0 --port 8000; fi; ')

check('no framework falls back to the legacy guesser',
        PythonLaunch.startLine('', 0, ['app/main.py': fastapiModule], ''),
        PythonLaunch.GUESS_LAUNCH_LINE)

check('plain python framework falls back to the legacy guesser',
        PythonLaunch.startLine('python', 8000, ['app/main.py': fastapiModule], ''),
        PythonLaunch.GUESS_LAUNCH_LINE)

check('a nested module that builds no ASGI app falls back',
        PythonLaunch.asgiLaunchPrefix('fastapi', 8000, ['app/main.py': 'print("hello")\n']),
        null)

check('an explicit start command wins over everything',
        PythonLaunch.startLine('fastapi', 8000, ['app/main.py': fastapiModule], 'python -m myservice'),
        'python -m myservice')

check('missing port defaults to 8000',
        PythonLaunch.asgiLaunchPrefix('fastapi', 0, ['app/main.py': fastapiModule]),
        'if command -v uvicorn >/dev/null 2>&1; then exec uvicorn app.main:app --host 0.0.0.0 --port 8000; fi; ')

check('main.py wins over api.py one directory down',
        PythonLaunch.nestedCandidates(['app/api.py', 'app/main.py', 'srv/asgi.py']),
        ['app/main.py', 'srv/asgi.py', 'app/api.py'])

check('two levels down are not candidates',
        PythonLaunch.nestedCandidates(['a/b/main.py']),
        [])

println failures == 0 ? "\nALL GREEN" : "\n${failures} FAILING"
System.exit(failures == 0 ? 0 : 1)

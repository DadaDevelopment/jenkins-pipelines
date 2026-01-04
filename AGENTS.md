# Repository Guidelines

## Project Structure & Module Organization
- `vars/pythonPipeline.groovy`: Jenkins shared-library entrypoint for Python services (venv/uv setup, pytest, Sonar, Docker build, ArgoCD deploy).
- `vars/springPipeline.groovy`: Entry point for Spring services (JDK selection, Maven build + Sonar, Docker + ArgoCD).
- `lib/`: Local Groovy 5 jars for IDE completion; keep untracked as per `.gitignore`.
- `.idea/`: IDE settings; avoid editing unless changing project-wide tooling.

## Build, Test, and Development Commands
- No compile step is required; Jenkins loads the Groovy files directly.
- Quick syntax check: `groovyc -cp lib/* vars/pythonPipeline.groovy` (adjust file path as needed).
- Recommended local smoke for Python projects the pipeline will run: `python3 -m venv .venv && . .venv/bin/activate && pip install -r requirements.txt && pytest`.
- For Spring projects: `mvn clean verify -ntp` from the service repo to match pipeline expectations.

## Coding Style & Naming Conventions
- Language: Groovy 5 for pipelines; prefer 4-space indentation and explicit `def` for pipeline variables.
- Keep pipeline steps idempotent and guarded by `fileExists`/`isPR` checks, mirroring existing patterns.
- Configuration keys in `config` maps should be lowerCamelCase (e.g., `projectName`, `submodule`, `python`, `deploy`).
- Avoid hardcoded credentials or URLs; use Jenkins credentials IDs and tools (`pip-nexus-settings`, `uv-nexus-settings`, `SonarScanner`, Argo git SSH URL) already referenced in scripts.

## Testing Guidelines
- The repository currently has no automated tests; when adding logic, favor JenkinsPipelineUnit/Spock tests under `test/` to validate stages and environment guards.
- Keep test names descriptive (`shouldDoX_whenY`) and ensure any added tests run headless with mocked Jenkins steps.
- In pipelines, keep run-time test discovery (e.g., pytest) behind existence checks as in `pythonPipeline`.

## Commit & Pull Request Guidelines
- Follow observed convention: prefix with ticket ID and brief summary (e.g., `DADA-327 - describe change`); use imperative phrasing after the ticket.
- For PRs, include: purpose and scope, affected services/pipelines, validation done (commands or Jenkins run link), and rollback notes if deployment logic changed.
- Attach sample Jenkins console excerpt or dry-run output when altering deployment steps; call out new credentials/tool requirements explicitly.

## Security & Configuration Tips
- Never echo secrets; rely on `withCredentials` and Jenkins tool installations.
- If modifying deploy destinations (Argo values paths, Nexus registry), make them configurable via `config` parameters and document defaults inline.
- Keep workspace cleanup (`cleanWs()`) at the start of nodes to avoid credential/file leakage between builds.

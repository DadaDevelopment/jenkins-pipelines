# TODO

- [x] Inspect python and spring pipeline implementations.
- [x] Move spring pipeline execution into a Kubernetes pod template.
- [x] Verify the updated Groovy compiles locally.
- [x] Verify a Jenkins spring build succeeds after the change.
- [x] Extract the shared podTemplate wrapper into a common helper.
- [x] Update spring, python, and js pipelines to use the helper without changing behavior.
- [x] Re-run Groovy syntax/build verification for the touched pipeline files.

## Review

- Local `groovyc` validation passed in a container.
- Jenkins replay build `DADA-TUDA/spring.event-fabrique/PR-27 #4` finished `SUCCESS` with the podTemplate-based spring pipeline.
- `groovyc` passed for `vars/kubePodTemplate.groovy`, `vars/springPipeline.groovy`, `vars/pythonPipeline.groovy`, and `vars/jsPipeline.groovy` after moving the pod YAML into the shared helper.
- `python` and `node` pod profiles now ask for materially higher `ephemeral-storage` so they avoid almost-full nodes; `docker` and `dind` also declare explicit requests/limits.

## 2026-08-23 — Dada Build agent priority

- [x] Trace the actual `dada-build-agent` template and its live priority.
- [x] Restore the non-preempting `ci-agent` PriorityClass in that template.
- [x] Validate Groovy syntax and the rendered embedded pod YAML.
- [ ] Push the owned change and verify a newly created agent pod.

### Review

- Live `ci-agent` is value `100000` with `preemptionPolicy: Never`.
- Build #484's generated pod had no `priorityClassName`; it was Jenkins-terminated after connecting, not Kubernetes-evicted.
- The documented classpath check conflicts with local Groovy 5.0.0/5.0.7 jars. Isolated `groovyc -cp src` and Ruby YAML parsing both passed.

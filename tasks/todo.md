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
- IDE `build_project` passed for `vars/kubePodTemplate.groovy`, `vars/springPipeline.groovy`, `vars/pythonPipeline.groovy`, and `vars/jsPipeline.groovy`.
- `groovyc` in the desktop terminal hit an environment Groovy module-version conflict, so IDE-backed compilation was used instead.

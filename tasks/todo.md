# TODO

- [x] Inspect python and spring pipeline implementations.
- [x] Move spring pipeline execution into a Kubernetes pod template.
- [x] Verify the updated Groovy compiles locally.
- [x] Verify a Jenkins spring build succeeds after the change.

## Review

- Local `groovyc` validation passed in a container.
- Jenkins replay build `DADA-TUDA/spring.event-fabrique/PR-27 #4` finished `SUCCESS` with the podTemplate-based spring pipeline.

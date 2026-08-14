# AGENTS Instructions

Cross-cutting workflow rules (build verification, IntelliJ inspections, re-read disciplines, agent-instruction propagation) are in `../workspace/AGENTS.md`.

## Repo-specific

### The PII log guard runs over these tests

`JiottyPiiLogGuardExtension` (in `jiotty-common`'s test sources, registered via `META-INF/services/org.junit.jupiter.api.extension.Extension`) attaches an
appender to the `net.yudichev` tree, raises it to `DEBUG` so every line renders, and fails a test when a rendered line carries an email address, a VIN or a
precise coordinate. It reaches any module with `jiotty-common`'s test-jar on its test classpath; add that dependency to a module whose code handles personal
data.

- **It activates only where `pii.log.guard.enabled` is set**, which `jiotty-parent`'s surefire configuration does. That property is what stops the extension —
  which ships inside a published test-jar — from scanning the logs of any project that consumes it.
- **Test logging must stay synchronous**, and a fixture has to look like real data for a leak to be recognisable. `car-engine/AGENTS.md` carries the fuller
  account of both.

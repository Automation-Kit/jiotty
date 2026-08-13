# Contributing to jiotty

Issues and pull requests are welcome. jiotty is a personal-scale project that backs a real house, so the bar is
"someone else can maintain this in two years", not "it compiles".

## Before you start

- **Open an issue first for anything larger than a bug fix.** A new connector is a long-term maintenance commitment; it
  helps to agree on the shape of its module and builder before you write it.
- Small fixes — a broken link, a wrong default, an obvious bug — need no preamble. Send the pull request.

## Building

Requirements: **JDK 25** and Git. Maven comes with the wrapper.

```bash
./mvnw -T1C verify
```

`verify` must pass before you open the pull request; CI runs the same command on every push and pull request. It also
runs the dependency analysis, which fails the build on undeclared or unused dependencies. Some tests start real
backends in-process (PostgreSQL, an MQTT broker), so no Docker is needed.

## Conventions

The existing code is the reference; the points below are the ones worth stating out loud.

- **A component is a public interface plus a public implementation with a public constructor.** Guice is the
  recommended wiring, not a requirement — anyone must be able to construct your component by hand.
- **One Guice module per component**, built through a typed builder, exposing exactly one key
  (`BaseExposedKeyModule` + `BaseModuleBuilder`). Builder parameters are `BindingSpec`s, so callers can pass a literal
  or a binding.
- **Anything holding a resource — a socket, a thread, a subscription — extends `BaseLifecycleComponent`**, acquires in
  `doStart()` and releases in `doStop()`, in reverse order.
- **Production code does not block.** `doStart()` is the single exception, and even there a wait needs a finite
  timeout. Everything else composes `CompletableFuture`s.
- **Remote calls get a timeout and a retry policy** (`RetryableOperationExecutor` with an explicit `maxElapsedTime`),
  and transient failures are surfaced to the caller rather than only logged.
- **Library code does not log at WARN or ERROR** — that decision belongs to the application consuming the library.
- **No secrets or personal data in logs.** Redact with `LogRedaction`.
- **British English** in identifiers and comments.

## Tests

Every new class gets a sibling test next to it (`src/test/java/.../<ClassName>Test.java`), Guice modules included — a
module test that builds the injector catches most wiring mistakes on its own. Use JUnit 5 with AssertJ, and drive time
and executors through `ProgrammableClock` rather than real threads or `Thread.sleep`.

## Dependencies

New third-party dependencies must be the latest **stable (GA)** release — no release candidates or milestones. Version-
manage anything shared from `jiotty-bom` or `jiotty-parent` rather than repeating a version across modules. Every
GitHub Action is pinned by commit SHA with its release in a trailing comment.

## Licence

By contributing you agree that your contributions are licensed under the [Apache License 2.0](LICENSE), the same terms
that cover the rest of the project.

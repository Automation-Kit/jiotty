# AGENTS Instructions

## Tests

- Use AssertJ (`org.assertj.core.api.Assertions`) for test assertions; avoid Hamcrest matchers in new or updated tests.
- Treat components as black boxes; use only public APIs and avoid reflection.
- Factor shared test setup/teardown into `@BeforeEach`/`@AfterEach` or shared fixtures/extensions; do not repeat the same setup in each test method.
- Use `Closeable.closeIfNotNull(...)`/`Closeable.closeSafelyIfNotNull(...)` (varargs) for test cleanup; prefer `Closeable.forActions(...)` for teardown steps.
- Use `MoreThrowables.asUnchecked(...)`/`getAsUnchecked(...)` instead of try/catch that only wraps checked exceptions in `RuntimeException`.
- Always statically import `TimeUnit` constants (for example, `SECONDS`) instead of `TimeUnit.SECONDS`.
- In tests, reuse production constants by making them package-private instead of re-declaring them.
- If a scenario suggests a possible bug but is not certain, ask for confirmation before encoding it in a test.
- Build comprehensive coverage with edge cases and failure/timeout paths.
- Run jacoco or similar tool to uncover branches that do not have coverage and add tests for such scenarios. If the scenario is not possible, then analyse why
  and add a TODO in the code to investigate later.

## Design and Style

- If extracting an interface from a class `MyClass`, rename the class to `MyClassImpl` and use `MyClass` for the interface.
- Public components must be declared as interfaces and an implementation class. The class should be named as `<InterfaceName>Impl`, unless there is a chance of
  multiple alternative implementations, in which case these should be named distinctly.
- Prefer Guava `checkArgument()`/`checkState()` over `if (...) throw IllegalArgumentException/IllegalStateException`, and use `instanceof` pattern variables
  instead of casts when possible.
- Module builder setters of mandatory parameters must start with `set`. Setters for optional parameters must start with `with`, and all `with` setters must be
  declared after the `set` setters.
- Only add documentation comments when they convey non-obvious information (contracts, invariants, side effects, performance). Do not add documentation that
  restates the signature; omit it even for public API.
- Services that compute something, use I/O or otherwise perform long-running tasks must use the async interface by returning a `CompletableFuture`.
- Use Markdown documentation comments (`///`); do not use `/**` or HTML tags.
- Do not add Javadoc to record components; when record components need documentation, use `@param` tags in the record Javadoc and only add it if it adds value.
- Do not use `{@code}` tags in Markdown Javadoc, use backticks instead.
- Do not use `java.util.Stream` API in prod code on hot paths (for example, in `Node.wave()`); it is OK in initialisation/shutdown code or in tests.
- When adding a new method that can be static, always declare it as static.
- Use British English for identifiers and comments.
- Only use `var` to declare local variables when the type of the variable is immediately clear from the expression to the right of the `=`.
- When the right-hand side is `new Type(...)`, use `var`.
- The right margin for code is 160 characters.
- Method or record parameters are chopped vertically if they don't fit on one line, both when declaring and when invoking a method. There is typically no line
  break before the first parameter, unless adding it makes the whole thing fit on one line.
- Always statically import static methods in `net.yudichev.jiotty.common.inject.BindingSpec`.
- Generally, do not create private constants that are only used once. This creates indirection that makes the code harder to read.
- When the same logic repeats in a class, extract a helper method instead of duplicating the snippet.
- Do not store values as fields if they are only used to build other fields in the constructor; keep them as constructor-local variables.
- in all methods, private or public, use `jakarta.annotation.Nullable` on values that can be nullable by design, both return value types and method parameters.
  For return types, add annotation on the type, not on the method, i.e. place it immediately before the return type.
- Non-nullable constructor arguments must always be validated via `checkNotNull` and, where applicable, other basic checks must be performed like checking for
  blank strings, negative integers etc. For records it must be done in the compact constructor.
- When creating a `StringBuilder`, estimate the buffer size; only use the default constructor when an estimate is not practical.
- SQL statements should be defined as constants or fields; do not build concatenated SQL strings inside methods.
- Avoid blocking API - it is not compatible with `ProgrammableClock`-based tests. In lifecycling methods like `BaseLifecycleComponent.toStart() and doStop()` it
  is allowed, but all the blocking methods must have a timeout. No no-arg calls to `Thread.join()` or `CompletableFuture.join()`.
- When shutting down resources, for example in `BaseLifecycleComponent.doStop()`, always do it in the reverse order of their initialisateion.

## Git hygiene

- Always add new source/config files you create to git before responding, unless they are explicitly marked as non-shareable (e.g., secrets in `.env`).
- Do not add temporary or local-only files (e.g., `~`, `tmp`, logs, build outputs) unless explicitly asked.
- Before responding, run `git status --short` and stage any new files you created (excluding non-shareable or temporary files).

## Executing external tools

- Prefer quiet modes for noisy tools during reasoning. Tools that write a lot to stdout (for example Maven downloading dependencies) slow down IDE AI
  integration. Use warning/error-only output unless detailed logs are needed.

## Dependencies

- When changing any library, always recompile dependent repositories that you know of.

## Misc

- After code changes, run a build (at minimum `compile`) before responding unless explicitly told not to.

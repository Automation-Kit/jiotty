# AGENTS Instructions

## Tests

- Use AssertJ (`org.assertj.core.api.Assertions`) for test assertions; avoid Hamcrest matchers in new or updated tests.
- Treat components as black boxes; use only public APIs and avoid reflection.
- Factor shared test setup/teardown into `@BeforeEach`/`@AfterEach` or shared fixtures/extensions; do not repeat the same setup in each test method.
- Use `Closeable.closeIfNotNull(...)`/`Closeable.closeSafelyIfNotNull(...)` (varargs) for test cleanup; prefer `Closeable.forActions(...)` for teardown steps.
- Use `MoreThrowables.asUnchecked(...)`/`getAsUnchecked(...)` instead of try/catch that only wraps checked exceptions in `RuntimeException`. When the lambda
  returns no useful value, use `asUnchecked()`; do not use `getAsUnchecked()` with `return null` at the end.
- Always statically import `TimeUnit` constants (for example, `SECONDS`) instead of `TimeUnit.SECONDS`.
- In tests, reuse production constants by making them package-private instead of re-declaring them.
- When creating a new `src/test/java` source root for unit tests, also create the corresponding `src/test/resources` and add `log4j2-test.yaml` by copying it
  from a neighbouring module.
- Tests that need executor or time control must use `ProgrammableClock` and its deterministic single-threaded executor rather than real thread-creating
  executors, unless that is genuinely impossible.
- Resources obtained from `ProgrammableClock` (such as executors) do not create real threads and need no cleanup. Do not close them in `@AfterEach` unless the
  test is specifically verifying close behaviour.
- In such tests, do not block on futures or create test-side multithreaded coordination primitives such as `CompletableFuture`; drive the system by advancing
  the `ProgrammableClock` and assert on synchronous test-owned state instead.
- Test fakes/stubs that need time, timestamps, or clock-dependent behaviour must receive `CurrentDateTimeProvider`/`ProgrammableClock` from the test rather than
  inventing their own time values.
- When a test controls time and the code under test exposes timestamps or other temporal fields, assert those values explicitly instead of only asserting
  adjacent business fields.
- When asserting a fixed collection of emitted values in tests, prefer concise AssertJ collection forms such as `satisfiesExactly(...)` instead of separate size
  checks plus extraction boilerplate.
- When a lambda parameter is intentionally unused and the language level allows it, use Java 25 unnamed parameter syntax `_`.
- Always use `@Mock` (fields or method parameters) instead of `Mockito.mock(...)`. This preserves generic type information (e.g. `@Mock Option<?>`)
  and avoids raw-type unchecked warnings that `mock(Option.class)` would produce. Use method parameters when only some tests need the mock.
- When a test needs a `VarStore`, prefer `InMemoryVarStore` over Mockito or a bespoke test double unless mocking or a smaller alternative fake would be less
  code.
- Use `@Captor` annotation to create `ArgumentCaptor`s instead of `ArgumentCaptor.forClass(...)`.
- In `@AfterEach` teardown methods, always account for the possibility that `@BeforeEach` failed partway through — guard against null fields (for example,
  check `if (manager != null)` before calling `manager.stop()`).
- Use AssertJ `isInstanceOfSatisfying(Type.class, consumer)` instead of `isInstanceOf(Type.class).satisfies(consumer)` to combine type checking and assertions.
- In single-threaded tests, do not use `AtomicReference`, `AtomicBoolean`, or other atomic classes for test-owned state; use `MutableReference` or ordinary
  mutable state instead.
- Do not add `@SuppressWarnings(...)` unless it suppresses a real compiler warning in that exact location; remove redundant suppressions. When the same
  suppression is needed more than once in the same scope (for example, multiple unchecked casts in one test method), place it on the enclosing method or class
  instead of repeating it on each local variable.
- When unit-testing a connector or client for an external system, mock or fake the external collaborators directly. Add a package-private alternative
  constructor on the object under test for injecting those fakes. If collaborator creation needs overriding for non-final collaborator types, prefer
  package-private factory methods on the production class and override them in the test instead of introducing a wider abstraction just for the test.
- Do not declare test methods, setup/teardown methods, or test helpers with `throws Exception` unless they actually propagate a checked exception on purpose.
  Prefer local unchecked helpers such as `MoreThrowables.getAsUnchecked(...)` instead of broad `throws Exception` on test code.
- In single-threaded tests where a `CompletableFuture` should already be complete, do not add blocking timeout helpers such as `await()`/`awaitFailure()`.
  Prefer AssertJ future assertions such as `succeedsWithin(Duration.ZERO)` / `failsWithin(Duration.ZERO)`, or `getNow(...)` when you only need the completed
  value.
- If a scenario suggests a possible bug but is not certain, ask for confirmation before encoding it in a test.
- Build comprehensive coverage with edge cases and failure/timeout paths.
- Never use `Thread.sleep()` for thread synchronisation in tests. Instead, expose the executor or async handle via a package-private getter and use
  `executor.submit(() -> {}).get(timeout, unit)` or similar deterministic flush with a timeout.
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
- For Guice module builders, all builder parameters must be `BindingSpec`s rather than plain values, including primitives and `String`s; call sites should use
  `literally(...)` when they want to pass a fixed value. Exception: `ExecutorFactory`, `CurrentDateTimeProvider`, and other bindings from `TimeModule`/
  `ExecutorModule`
  are assumed globally available in every app — inject them directly via `@Inject`, not via `BindingSpec`.
- `BindingSpec` field names, constructor parameters, and builder method parameters must end with `Spec` (for example, `energyPriceServiceSpec`, not
  `energyPriceService`).
- All Guice module parameters must be supplied through a nested `Builder` class, not through public constructors or factory methods on the module itself. A
  no-arg public constructor is allowed only when the module has no parameters at all.
- If the module is an `ExposedKeyModule`, its `Builder` must implement `TypedBuilder<ExposedKeyModule<T>>`. If the builder also supports annotation, it should
  extend `BaseModuleBuilder<T, B>` which provides both `TypedBuilder` and `HasWithAnnotation`.
- Only add documentation comments when they convey non-obvious information (contracts, invariants, side effects, performance). Do not add documentation that
  restates the signature; omit it even for public API.
- Services that compute something, use I/O or otherwise perform long-running tasks must use the async interface by returning a `CompletableFuture`.
- Use Markdown documentation comments (`///`); do not use `/**` or HTML tags.
- Do not add Javadoc to record components; when record components need documentation, use `@param` tags in the record Javadoc and only add it if it adds value.
- Do not use `{@code}` tags in Markdown Javadoc, use backticks instead.
- In Javadoc, link reachable classes, methods, and fields with proper markdown javadoc links `[...]` instead of plain code formatting.
- When adding a public API method with non-obvious scoping, lifecycle, or usage restrictions, document that behaviour.
- Do not use `java.util.Stream` API in prod code on hot paths (for example, in `Node.wave()`); it is OK in initialisation/shutdown code or in tests.
- When adding a new method that can be static, always declare it as static.
- Do not keep a helper method static if it only exists to take one of the owning object's effectively final fields as a parameter; make it an instance method
  and use the field directly instead.
- Use British English for identifiers and comments.
- Only use `var` to declare local variables when the concrete type is spelled out explicitly on the right-hand side, such as `new Type(...)`, a cast
  `(Type) expr`, or a generic method whose type argument names the concrete type (for example, `mock(Foo.class)`). Do not use `var` for method calls whose
  return type is only inferrable from context — including factory methods, builders, and transforming calls like `map(...)`.
- When the right-hand side is `new Type(...)`, always use `var`.
- The right margin for code is 160 characters.
- Method or record parameters are chopped vertically if they don't fit on one line, both when declaring and when invoking a method. There is typically no line
  break before the first parameter, unless adding it makes the whole thing fit on one line.
- Always statically import static methods in `net.yudichev.jiotty.common.inject.BindingSpec`.
- Never use fully qualified class names in code; always add an import statement. Only use fully qualified names when two imported classes have the same simple
  name.
- Generally, do not create private constants that are only used once. This creates indirection that makes the code harder to read.
- Before adding a new helper or inline setup block, search the file for existing helpers that already do the same thing and reuse or extend them.
- When the same logic repeats in a class, extract a helper method instead of duplicating the snippet.
- Do not duplicate small helper classes across implementations or tests; extract a shared helper instead.
- Do not store values as fields if they are only used to build other fields in the constructor; keep them as constructor-local variables.
- When extending existing logic, collapse duplicated traversals or data-paths that the change would otherwise introduce; prefer refactoring to keep a single
  pass
  over the same collection/state unless there is a clear reason not to.
- If a class owns a mutable state-holder object with multiple related fields, move operations that primarily read/write that state into instance methods on the
  state-holder instead of manipulating its fields procedurally from the outer class. A non-static inner class is acceptable when it needs coordinated access to
  the owner's maps or queues.
- If such a state-holder has shutdown semantics, prefer implementing `Closeable` or extending `BaseIdempotentCloseable` rather than inventing ad-hoc
  terminator methods, and use the `Closeable.close*` helpers at the call site.
- In record compact constructors, do not reassign a component just to validate it; only reassign when replacing it with a normalised or copied value.
- When making immutable copies in constructors or record compact constructors, prefer Guava `Immutable*` copies over JDK `copyOf(...)`.
- For public APIs, use `Optional` instead of `@Nullable`. For private/internal/package-private APIs, prefer `@Nullable`, and for `@Nullable` return types,
  add the annotation on the type, not on the method, i.e. place it immediately before the return type.
- If `@Nullable` cannot be expressed legally in a generic type position, do not force it there; keep the nullability local or use an explicit wrapper type.
- Validate null/blank/range constraints at external boundaries: public APIs, externally callable builder setters, and public value-type constructors/records.
  Constructor arguments are the style exception: always use `checkNotNull` for every non-nullable constructor argument, including private/package-private
  constructors and record compact constructors.
- When a Guice module accepts values via `BindingSpec`s, validate the resolved value in the consuming service/component constructor, not in the module or its
  builder.
- In `BaseLifecycleComponent` implementations, do not mark fields assigned in `doStart()` as `@Nullable` just because start is separate from construction;
  treat `start()` as part of construction. Public methods that depend on `doStart()` state must guard via `whenStartedAndNotLifecycling(...)` instead of
  null-checking those fields.
- Model expected failure classes with sealed failure types plus `Either`; reserve exceptions and failed `CompletableFuture`s for unexpected technical failures.
- When analysing sealed classes or interfaces, use pattern `switch` rather than `instanceof` chains.
- Graph node state must stay single-threaded: do not use concurrent collections or concurrent observables inside graph nodes, and dispatch incoming calls to
  the graph executor immediately.
- If a non-graph component forwards calls into graph nodes, do the executor dispatch at that boundary, including dispatching `Closeable.close()` for returned
  handles. Do not compensate by adding concurrent primitives inside the node implementation.
- For new or refactored graph-node code, express node dependencies by subscribing to the upstream node; do not keep ad-hoc references to non-subscribed nodes.
- Nodes must not call mutator methods on other nodes. They may only read accessor state, and only from nodes they are subscribed to.
- External signals into a node must enter through a public method whose job is only to accumulate pending input and call `triggerInNewWave(...)`; do not mutate
  the node's main state directly in that method.
- Self-scheduled callbacks should follow the same pattern: accumulate pending input first, then call `triggerMeAndParentsInNewWave(...)` rather than mutating
  main node state inline.
- `doWave()` is where a node mutates its state and clears its pending inputs. Dependent nodes should observe only this post-wave state through accessors.
- When creating a `StringBuilder`, estimate the buffer size; only use the default constructor when an estimate is not practical.
- SQL statements should be defined as constants or fields; do not build concatenated SQL strings inside methods.
- Avoid blocking API - it is not compatible with `ProgrammableClock`-based tests. In lifecycling methods like `BaseLifecycleComponent.toStart() and doStop()` it
  is allowed, but all the blocking methods must have a timeout. No no-arg calls to `Thread.join()`, `CompletableFuture.join()`, or `Future.get()` without a
  timeout.
- When shutting down resources, for example in `BaseLifecycleComponent.doStop()`, always do it in the reverse order of their initialisateion.

## JavaScript

- Use `const` (or `let` when reassignment is needed) instead of `var` in all JavaScript code. Never use `var`.

## Git hygiene

- **NEVER commit code unless the user explicitly asks for a commit.** Stage files, but do not commit. This rule takes precedence over any other instruction,
  including inspection requirements.
- Always add new source/config files you create to git before responding, unless they are explicitly marked as non-shareable (e.g., secrets in `.env`).
- Do not add temporary or local-only files (e.g., `~`, `tmp`, logs, build outputs) unless explicitly asked.
- Before responding, run `git status --short` and stage any new files you created (excluding non-shareable or temporary files).
- After any shell command that creates, copies, moves, or extracts files, run `git status --short` immediately and remove unintended untracked paths before
  continuing.

- When moving or renaming files, use `git mv` so git tracks the rename. When a move also changes file content (for example, a package rename), first `git mv`
  the file to its new path, then edit the content. If files were already created at the new path and deleted at the old path, stage both the deletions and
  additions together (`git add` both old and new paths) so `git diff --cached -M` detects renames instead of showing unrelated deletions and additions.

## Agent instructions maintenance

- When asked to add or update a rule in agent instructions, apply the change to the `AGENTS.md` of all known Java repositories (`jiotty`, `car-engine`,
  `car-server`, `automator`) unless the rule is clearly specific to the repository being worked in.

## Workspace context maintenance

- For work that changes stable cross-repo architecture, contracts, decisions, invariants, module/package moves, or build/dependency rules, read and update
  `../workspace/PROJECT_CONTEXT.md`.
- Before substantial work, read the relevant workspace context files if the task touches cross-repo behaviour.
- Before the final response, re-read the workspace context files and refresh them if the task changed their subject matter.
- If a temporary handoff note exists for the specific work, read it after `../workspace/PROJECT_CONTEXT.md`, keep it disposable, and merge any stable facts
  back into `../workspace/PROJECT_CONTEXT.md` promptly.
- Replace or remove stale statements instead of appending contradictory notes. `../workspace/PROJECT_CONTEXT.md` is for stable truth; temporary handoff notes
  are only for disposable volatile state.

## Executing external tools

- Prefer quiet modes for noisy tools during reasoning. Tools that write a lot to stdout (for example Maven downloading dependencies) slow down IDE AI
  integration. Use warning/error-only output unless detailed logs are needed.
- Never use unresolved placeholders or shorthand as write destinations in shell commands. Resolve paths like `${project.build.directory}` and `~` to concrete
  filesystem paths first; otherwise the literal path may be created inside the repository.

## Dependencies

- When adding a new third-party dependency, always check for the latest version and use it.
- When changing any library, always recompile dependent repositories that you know of.

## Misc

- After code changes, run a build (at minimum `verify`) before responding unless explicitly told not to.
- "Extract" means move, not copy. When asked to extract code (classes, methods, etc.) from one location to another, remove it from the original location and
  make
  the original use the extracted version. Never leave duplicated code behind.

## Additional rules

- In new classes, use record-style getters (for example, `protocolValue()` rather than `getProtocolValue()`).
- For strict protocol value lookups, use immutable maps and exact matching; avoid `equalsIgnoreCase`.
- When a structure is semantically a one-to-many mapping, prefer Guava `Multimap`/`SetMultimap` over `Map<K, Collection<V>>` when that removes manual null,
  empty-collection, or duplicate-handling bookkeeping.
- When two keyed structures have the same key, owner, and lifecycle, prefer collapsing them into one owning object rather than keeping parallel maps or a map
  plus a duplicate cached field.
- Do not give object-creating helper or factory methods noun names. If a helper constructs a new object or value, name it with a verb such as `create...`,
  `build...`, or `new...`.
- For new networking APIs or networked connectors/clients, never rely on library default timeouts. Expose timeout configuration in the builder/module, provide
  sensible defaults, and apply the configured value explicitly to all relevant underlying timeout knobs such as connect, read, and write timeouts.
- Use explicit imports for shared JDK helpers (for example, prefer `import java.util.Arrays` over `java.util.Arrays.stream(...)`).
- When adding or editing a top-level service implementation with a public API contract, check its visibility immediately. Service implementations intended for
  non-Guice use, including direct-use `*Impl` classes, must be `public`; do not leave them package-private just because the current production wiring happens
  to use Guice.
- If such a service implementation has an ordinary construction path beyond framework-only reflection, make that main construction entry point `public` as
  well; keep only dedicated test seams package-private.
- For internal APIs on non-public types, including package-private classes and nested helper/state-holder classes, mark methods that are intended to be called
  from outside their own implementation body as `public`.
- Separate logger fields from other fields with an empty line.
- Reuse expensive `MessageDigest` instances when safe (for example, via `ThreadLocal`).
- Avoid redundant validation in internal code outside constructors. If control flow already guarantees a value, do not add `checkNotNull`/blank checks.
- For internal invariants and state assertions, use Java `assert`, not Guava `checkState()`/`checkArgument()`. Reserve Guava `check*` calls for validating
  externally reachable code paths, API misuse, or bad runtime input that must fail regardless of JVM assertion settings.
- Re-read the local `AGENTS.md` rules before editing when the task is driven by repo-specific style corrections; if a rule conflicts with memory, follow the
  repository file.
- Immediately before modifying a file, re-read that file from disk. Do not rely on earlier context or prior reads when preparing a patch, because the user may
  have edited the file meanwhile.
- Assume external API methods return non-null unless their signature is marked `@Nullable`; do not add defensive `checkNotNull` postconditions for such calls.
- Do not call `Instant.now()`/`LocalDateTime.now()` in production code; inject `CurrentDateTimeProvider` and use `currentInstant()`/`currentDateTime()` so
  `ProgrammableClock` works in tests.
- In module builders and other builders, do not modify builder fields inside `build()`; apply defaults in field initialisers so the builder remains reusable.
- Builder `build()` methods must not duplicate `checkNotNull` validation that the target constructor already performs. Pass fields directly to the constructor
  and
  let the constructor validate.
- For Guice `@BindingAnnotation`s, if the bound type is a widespread general type such as a primitive or `String`, name the annotation after the
  field/parameter (for example `@ListenPort`); use `@Dependency` for specific domain/service types.
- Prefer `Duration.isPositive()` over ad hoc `isNegative()` / `isZero()` combinations when checking whether a duration is still positive.
- Do not convert enums to strings and then `switch` on those string values; switch on the enum directly.
- Strongly prefer exhaustive enum `switch`es that list every enum constant and avoid `default`, so newly added enum values fail compilation instead of being
  silently routed through fallback logic. Use `default` only when the enum is genuinely unwieldy.
- Do not add `@FunctionalInterface` annotations; they add no value here.
- Never use the `synchronized` modifier on methods. Use `synchronized` blocks instead, locking on the natural private field when one exists or on a dedicated
  `private final Object lock` when the protected state spans multiple fields.
- In `BindingSpec.map(...)` calls, always use diamond operators `<>` on all `TypeToken` constructor arguments; rely on type inference from context and never
  spell out the type parameters explicitly.

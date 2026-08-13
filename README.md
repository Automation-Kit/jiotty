# jiotty

[![Build][build-badge]][build-url]
[![Maven Central][maven-badge]][maven-url]
[![License][license-badge]][license-url]
[![Java][java-badge]][java-url]

[build-badge]: https://github.com/ylexus/jiotty/actions/workflows/build.yml/badge.svg
[build-url]: https://github.com/ylexus/jiotty/actions/workflows/build.yml
[maven-badge]: https://img.shields.io/maven-central/v/net.yudichev.jiotty/jiotty-common?label=Maven%20Central
[maven-url]: https://central.sonatype.com/search?namespace=net.yudichev.jiotty
[license-badge]: https://img.shields.io/badge/License-Apache%202.0-blue.svg
[license-url]: https://www.apache.org/licenses/LICENSE-2.0
[java-badge]: https://img.shields.io/badge/Java-25-orange
[java-url]: https://openjdk.org/projects/jdk/25/

**A Java toolkit for people who write their own home automation instead of configuring someone else's hub.**

jiotty gives you the two things a hand-written automation app needs: a small application runtime (dependency injection,
component lifecycle, single-threaded schedulers, retries, metrics, persistence) and a growing set of connectors to the
things around the house — smart plugs, thermostats, curtains, projectors, cars, energy tariffs, calendars, push
notifications. You write the logic; jiotty deals with the wiring, the shutdown ordering, the flaky HTTP endpoint and the
JSON.

- [Why](#why)
- [Installation](#installation)
- [Quick start](#quick-start)
- [Core concepts](#core-concepts)
- [Writing your own component](#writing-your-own-component)
- [Testing support](#testing-support)
- [Module catalogue](#module-catalogue)
- [Building from source](#building-from-source)
- [Compatibility and versioning](#compatibility-and-versioning)
- [Contributing](#contributing)
- [License](#license)

## Why

Automation hubs are excellent until you want something they do not do. Dropping to code gives you total flexibility and,
usually, a pile of boilerplate: OAuth dances, executors that never shut down, half-started components after a failed
startup, a device API that returns 500 every twentieth call.

jiotty's opinion is that all of that belongs in a library:

- **Every component ships a Guice module — and works without one.** Components are ordinary classes with public
  constructors, so hand-wiring is a first-class option; the module and its typed builder are the recommended shortcut.
- **Components have a lifecycle.** `LifecycleComponent`s are started in binding order at startup and stopped in reverse
  order at shutdown, including after a failed startup, so sockets, threads and subscriptions get released.
- **Concurrency is confined, not shared.** Components run their work on their own single-threaded scheduling executors
  and expose `CompletableFuture`s, rather than sharing mutable state behind locks.
- **Remote calls are assumed to fail.** Connectors ship with configurable back-off/retry, timeouts and health reporting.
- **It is used in anger.** The library backs the author's own house; every module here exists because something in it
  needed it.

## Installation

Artifacts are published to Maven Central under the `net.yudichev.jiotty` group id.

Import the BOM so all jiotty artifacts (and the third-party versions they agree on) are managed in one place:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>net.yudichev.jiotty</groupId>
            <artifactId>jiotty-bom</artifactId>
            <version>2.6.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

Then depend on just the modules you need — no version needed:

```xml
<dependency>
    <groupId>net.yudichev.jiotty</groupId>
    <artifactId>jiotty-common</artifactId>
</dependency>
<dependency>
    <groupId>net.yudichev.jiotty</groupId>
    <artifactId>jiotty-connector-tplinksmartplug</artifactId>
</dependency>
```

<details>
<summary>Using development snapshots</summary>

The `3.x` line is currently published as snapshots only:

```xml
<repositories>
    <repository>
        <id>central-snapshots</id>
        <url>https://central.sonatype.com/repository/maven-snapshots</url>
        <releases><enabled>false</enabled></releases>
        <snapshots><enabled>true</enabled></snapshots>
    </repository>
</repositories>
```

</details>

## Quick start

Blink a TP-Link smart plug on the local network. This is a complete `main` — `Application` builds the injector, starts
every lifecycle component, blocks until shutdown, then stops them in reverse order.

```java
import jakarta.inject.Inject;
import net.yudichev.jiotty.appliance.Appliance;
import net.yudichev.jiotty.common.app.Application;
import net.yudichev.jiotty.common.async.ExecutorModule;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.connector.tplinksmartplug.TpLinkSmartPlugModule;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.lang.CompletableFutures.logErrorOnFailure;

public final class Blink {
    public static void main(String[] args) {
        Application.builder()
                   .setName("blink")
                   // provides the executors that connectors schedule their work on
                   .addModule(() -> ExecutorModule.builder().build())
                   // the connector: exposes a single Appliance binding
                   .addModule(() -> TpLinkSmartPlugModule.localConnectionBuilder()
                                                         .setHost(literally("192.168.1.50"))
                                                         .build())
                   // your own logic
                   .addModule(() -> new BaseLifecycleComponentModule() {
                       @Override
                       protected void configure() {
                           registerLifecycleComponent(Blinker.class);
                       }
                   })
                   .build()
                   .run();
    }

    static final class Blinker extends BaseLifecycleComponent {
        private static final Logger logger = LogManager.getLogger(Blinker.class);

        private final Appliance plug;

        @Inject
        Blinker(Appliance plug) {
            this.plug = checkNotNull(plug);
        }

        @Override
        protected void doStart() {
            plug.turnOn()
                .thenCompose(_ -> plug.turnOff())
                .whenComplete(logErrorOnFailure(logger, "failed to blink the plug"));
        }
    }
}
```

Run it, hit `Ctrl-C`, and every component — including the executors — is shut down in reverse start order.

## Core concepts

### Guice is recommended, not required

A component is an ordinary Java class: a public interface, a public implementation, and a public constructor taking its
collaborators. Wire it by hand if that is what your application does — construct it, `start()` it, and `stop()` it when
you are done:

```java
var client = new HomeAssistantClientImpl("http://homeassistant.local:8123", accessToken);
client.start();
// … use it through the HomeAssistantClient interface, then:
client.stop();
```

The recommended route is the component's Guice module instead: a typed builder that takes the same configuration and
exposes exactly one key, so composition, lifecycle registration and teardown ordering come for free.

```java
ExposedKeyModule<Appliance> module = TpLinkSmartPlugModule.localConnectionBuilder()
                                                         .setHost(literally("192.168.1.50"))
                                                         .build();
```

Install that module in your application and inject `Appliance` wherever you need it. To pull a single instance out
without an application around it, resolve it through the exposed key:

```java
Appliance plug = Guice.createInjector(ExecutorModule.builder().build(), module)
                      .getInstance(module.getExposedKey());
```

Need two of them? Distinguish the exposures with binding annotations:

```java
ExposedKeyModule<Appliance> kitchenPlug = TpLinkSmartPlugModule.localConnectionBuilder()
                                                               .setHost(literally("192.168.1.50"))
                                                               .withAnnotation(forAnnotation(KitchenPlug.class))
                                                               .build();
```

Inject it as `@KitchenPlug Appliance`.

### Wiring values into modules with `BindingSpec`

Builder parameters take a `BindingSpec<T>` rather than a value, so a module can be configured with a literal in a
prototype and with a real binding in production without changing the module:

| Factory                                | Use when the value is…                          |
| -------------------------------------- | ----------------------------------------------- |
| `literally(value)`                     | a constant you already have                     |
| `providedBy(provider)`                 | produced by a `Provider` instance, class or key |
| `boundTo(Type.class)` / `boundTo(key)` | bound elsewhere in your injector                |
| `annotatedWith(MyAnnotation.class)`    | bound elsewhere under a binding annotation      |
| `exposedBy(otherModule)`               | exposed by another jiotty-style module          |

That last one is how modules compose — for example, feeding one connector's exposed client into another connector's
builder without either of them knowing about your injector.

### Lifecycle

`LifecycleComponent` is `start()` / `stop()`. `BaseLifecycleComponent` implements both and gives subclasses `doStart()`
and `doStop()` plus guards for the awkward moments:

| Helper                            | Purpose                                                                       |
| --------------------------------- | ----------------------------------------------------------------------------- |
| `checkStarted()`                  | fail fast on use before start / after stop                                    |
| `whenStartedAndNotLifecycling(…)` | run an action that must not race with start/stop                              |
| `ifNotStopped(…)`                 | run a producer callback that may arrive after stop, quietly dropping it if so |

`Application` finds every `LifecycleComponent` binding in the injector, starts them in binding order, and — on shutdown,
JVM signal, or a failed startup — stops the ones it managed to start, in reverse order, never propagating an exception
from `stop()`. `ApplicationLifecycleControl` is injectable and lets a component request an orderly shutdown or a full
restart (the injector is rebuilt from scratch).

If you would rather not use `Application`, do the same thing yourself:

```java
Injector injector = Guice.createInjector(modules);
List<LifecycleComponent> components = injector
        .findBindingsByType(new TypeLiteral<LifecycleComponent>() {})
        .stream()
        .map(binding -> binding.getProvider().get())
        .collect(toImmutableList());
components.forEach(LifecycleComponent::start);
// … and on termination, stop them in reverse order
```

### Async and scheduling

- `ExecutorFactory` (from `ExecutorModule`) creates named `SchedulingExecutor`s — single-threaded executors that both
  run tasks now and schedule them later, and that drain their immediate backlog on close while discarding pending
  scheduled work.
- Components confine their mutable state to their own executor thread instead of locking; results are handed out as
  `CompletableFuture`s.
- `JobScheduler` handles calendar-style recurring jobs; `TimeProvider` / `CurrentDateTimeProvider` abstract the clock so
  time can be driven deterministically in tests.
- `RetryableOperationExecutor` + `BackOffConfig` wrap a remote call in configurable exponential back-off, with a
  retryable-exception predicate per connector.

### Cross-cutting infrastructure in `jiotty-common`

`RestServer` (Javalin/Jetty) and `RestClients` for HTTP in and out; `Json` for Jackson plumbing; `ObservableValue`,
`Either`, `Optionals`, `Closeable`, `Listeners` and friends in `lang`; Micrometer metrics with a Prometheus endpoint and
JVM binders in `metrics`; `KeyStoreAccess` and `SslCustomisation` for TLS material; `LogRedaction` for keeping secrets
and personal data out of logs; `LatLon` in `geo`; and a wave-based reactive `Graph` engine for expressing automation
rules as a dependency graph of nodes that recompute only when their inputs change.

## Writing your own component

A component is a public interface (`Doorbell`) plus a public implementation (`DoorbellImpl`) whose public `@Inject`
constructor takes its collaborators — that constructor is what keeps the component usable without Guice. On top of it
sits the module, the shape every connector in this repository follows:

```java
public final class DoorbellModule extends BaseExposedKeyModule<Doorbell> {
    private final BindingSpec<String> hostSpec;

    private DoorbellModule(BindingSpec<String> hostSpec, SpecifiedAnnotation annotation) {
        super(annotation);
        this.hostSpec = checkNotNull(hostSpec);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected void configure() {
        hostSpec.bind(String.class)
                .annotatedWith(DoorbellImpl.Host.class)
                .installedBy(this::installLifecycleComponentModule);
        bind(exposedKey).to(registerLifecycleComponent(DoorbellImpl.class));
        expose(exposedKey);
    }

    public static final class Builder extends BaseModuleBuilder<Doorbell, Builder> {
        private BindingSpec<String> hostSpec;

        public Builder setHost(BindingSpec<String> hostSpec) {
            this.hostSpec = checkNotNull(hostSpec);
            return this;
        }

        @Override
        public ExposedKeyModule<Doorbell> build() {
            return new DoorbellModule(hostSpec, specifiedAnnotation());
        }
    }
}
```

`BaseExposedKeyModule` is a `PrivateModule`: everything you bind stays private unless you `expose` it, and
`registerLifecycleComponent` binds the implementation as a singleton and registers it for start/stop. If your component
controls something that can be turned on and off, implement `Appliance` from `jiotty-appliance` instead of a bespoke
interface — you then get `Command` metadata, `RetryingAppliance` and the appliance server for free.

## Testing support

- **`ProgrammableClock`** — a virtual clock that is *also* an `ExecutorFactory`. Advance time by hand and every
  scheduled task fires deterministically, on the calling thread. No `Thread.sleep` in tests.
- **JUnit 5 + AssertJ + Mockito** are the house test stack, managed by `jiotty-parent`.
- **Real backends, in-process**: WireMock for HTTP connectors, embedded PostgreSQL for the persistence modules, and an
  in-JVM Moquette broker for MQTT — no Docker required to run the build.
- **Test jars**: eleven modules — among them `jiotty-common`, `jiotty-user`, `jiotty-connector-firebase`,
  `jiotty-connector-mqtt` and the persistence and admin-alerts modules — publish `test-jar` artifacts carrying fakes and
  fixtures (`FakeFirebaseAuthConnector` and friends) that you can depend on from your own tests.
- Several connectors include a `*LocalRunner` in their test sources — small interactive `main`s that drive a real device
  or API from the console. They double as worked examples.

## Module catalogue

### Core

| Artifact                       | What it gives you                                                                                                        |
| ------------------------------ | ------------------------------------------------------------------------------------------------------------------------ |
| `jiotty-common`                | The runtime: DI and lifecycle, executors, scheduling, retry, JSON, REST, metrics, key stores, TLS, time, geo, node graph |
| `jiotty-bom`                   | Dependency management for every jiotty artifact and the third-party versions they agree on                               |
| `jiotty-appliance`             | `Appliance` / `Command` abstraction for anything that can be commanded, plus retrying and server-side wrappers           |
| `jiotty-process`               | Runs a child `Application` under a parent injector — restartable sub-applications                                        |
| `jiotty-logging`               | Runtime Log4j2 level configuration, persisted across restarts and exposed to the UI                                      |
| `jiotty-security`              | OAuth2 token manager: local login flow, token refresh, storage                                                           |
| `jiotty-user`                  | Multi-user UI server: displayables, options, push-device registry, per-user authorisation and rate limiting              |
| `jiotty-world`                 | Home location service                                                                                                    |
| `jiotty-energy`                | Energy price profiles and combination; Octopus Agile price and forecast services                                         |
| `jiotty-timeseries-cache`      | Typed time-series cache with resolutions, per-user/region/global scopes, schema versioning and negative caching          |
| `jiotty-admin-alerts-api`      | Operator alerting: raise and resolve alert bundles carrying severity, labels and event history                           |
| `jiotty-admin-alerts-logging`  | Logging backend for admin alerts                                                                                         |
| `jiotty-admin-alerts-postgres` | PostgreSQL backend for admin alerts, with an HTTP resolve endpoint                                                       |
| `jiotty-persistence-varstore`  | Key/value store (file or SQL) with per-user scoping, encryption at rest and GDPR export                                  |
| `jiotty-persistence-sql`       | PostgreSQL `DataSource` factory and domain schema initialisation/migration                                               |
| `jiotty-persistence-recording` | Recorders and readers for time-stamped records persisted to SQL                                                          |

### Connectors

| Artifact                            | Talks to                                                                             |
| ----------------------------------- | ------------------------------------------------------------------------------------ |
| `jiotty-connector-anthropic`        | Anthropic Messages API                                                               |
| `jiotty-connector-aws`              | AWS IoT MQTT messaging                                                               |
| `jiotty-connector-expo-push`        | Expo push notification service                                                       |
| `jiotty-connector-fieldglass`       | SAP Fieldglass timesheets                                                            |
| `jiotty-connector-firebase`         | Firebase Authentication — ID token verification, user records                        |
| `jiotty-connector-google-assistant` | Google Assistant — send a text phrase, get the spoken response                       |
| `jiotty-connector-google-calendar`  | Google Calendar                                                                      |
| `jiotty-connector-google-common`    | Shared Google OAuth authorisation flow used by the other Google connectors           |
| `jiotty-connector-google-drive`     | Google Drive                                                                         |
| `jiotty-connector-google-gmail`     | Gmail — messages, labels and attachments, plus a Log4j2 appender that emails logs    |
| `jiotty-connector-google-maps`      | Google Maps geocoding and Routes                                                     |
| `jiotty-connector-google-photos`    | Google Photos                                                                        |
| `jiotty-connector-google-sdm`       | Google Smart Device Management (modern Nest devices)                                 |
| `jiotty-connector-google-sheets`    | Google Sheets                                                                        |
| `jiotty-connector-home-assistant`   | Home Assistant REST API — climate, switch, number, button, sensors, history, logbook |
| `jiotty-connector-icloud`           | iCloud calendars over CalDAV                                                         |
| `jiotty-connector-ip`               | Host reachability monitoring (presence detection)                                    |
| `jiotty-connector-ir`               | Infrared blasters: BroadLink devices and LIRC servers                                |
| `jiotty-connector-miele`            | Miele@home appliances and their event stream                                         |
| `jiotty-connector-mqtt`             | Generic MQTT client, plus an MQTT-based presence service                             |
| `jiotty-connector-nest`             | Nest thermostat                                                                      |
| `jiotty-connector-octopusenergy`    | Octopus Energy accounts, tariffs, unit rates and price forecast sources              |
| `jiotty-connector-owntracks`        | OwnTracks location updates                                                           |
| `jiotty-connector-pushover`         | Pushover user alerts                                                                 |
| `jiotty-connector-rpi`              | Raspberry Pi GPIO via Pi4J                                                           |
| `jiotty-connector-shelly`           | Shelly smart plugs (as `Appliance`s)                                                 |
| `jiotty-connector-slide`            | Slide curtain motors, local and cloud                                                |
| `jiotty-connector-sonyprojector`    | Sony projectors (power control and state)                                            |
| `jiotty-connector-tesla`            | Tesla Fleet API, MQTT telemetry, TeslaMate database, Wall Connector                  |
| `jiotty-connector-tplinksmartplug`  | TP-Link smart plugs, local or cloud                                                  |
| `jiotty-connector-webclient`        | Headless browser scripting (HtmlUnit) for sites with no API                          |
| `jiotty-connector-world`            | Sunrise/sunset times and weather forecasts                                           |

## Building from source

Requirements: **JDK 25** and Git. Maven comes with the wrapper (Maven ≥ 3.8.7 is enforced if you use your own).

```bash
git clone https://github.com/ylexus/jiotty.git
cd jiotty
./mvnw -T1C verify
```

`-T1C` builds independent modules in parallel — the reactor still honours dependency order. `verify` is the goal to run:
besides the tests, it runs the dependency analysis that fails the build on undeclared or unused dependencies. Some tests
start real backends in-process (PostgreSQL, an MQTT broker), so the first build downloads a little more than usual.

Every push and pull request is built by the [Build workflow](.github/workflows/build.yml) on the same JDK.

## Compatibility and versioning

- Released artifacts: **2.6.0** on Maven Central. Development happens on the `3.x` line, published as snapshots.
- The source targets **Java 25** and will not run on older JVMs. Older releases target older JVMs.
- Guice is the dependency injection framework throughout; `jakarta.inject` annotations are used in application code.
- Nullness is documented with [JSpecify](https://jspecify.dev/) annotations.
- This is a personal-scale project: minor versions may change APIs when a better shape is found. Pin your versions and
  read the diff before upgrading.

## Contributing

Issues and pull requests are welcome.

- Open an issue first for anything larger than a bug fix — a connector is a long-term maintenance commitment, and it
  helps to agree on the shape of its module and builder up front.
- Follow the surrounding conventions: a public interface plus a public implementation with a public constructor (so the
  component works without Guice), one module per component exposing a single key, `BaseLifecycleComponent` for anything
  holding a resource, `BindingSpec` for builder parameters, and no blocking calls outside `doStart()`.
- Add tests. `./mvnw -T1C verify` must pass before you open the PR; CI runs the same command.
- New third-party dependencies should be the latest stable (GA) release; version-manage the shared ones in `jiotty-bom`
  or `jiotty-parent` rather than repeating a version across modules.

## License

Licensed under the [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0).

Copyright © Alexey Yudichev.
package net.yudichev.jiotty.connector.google.calendar;

import jakarta.inject.Inject;
import net.yudichev.jiotty.common.app.Application;
import net.yudichev.jiotty.common.async.ExecutorModule;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.keystore.KeyStoreAccessModule;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.CompletableFutures;
import net.yudichev.jiotty.common.security.AuthState;
import net.yudichev.jiotty.common.time.TimeModule;
import net.yudichev.jiotty.common.time.calendar.Calendar;
import net.yudichev.jiotty.common.time.calendar.CalendarService;
import net.yudichev.jiotty.common.time.calendar.CalendarService.CalendarsResult.Calendars;
import net.yudichev.jiotty.persistence.varstore.VarStoreModule;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.io.BufferedReader;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.nio.charset.StandardCharsets.UTF_8;
import static net.yudichev.jiotty.common.inject.BindingSpec.exposedBy;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;

/// Manual runner that exercises the real Google Calendar API end to end: a real [Application] wiring the real [GoogleCalendarModule] with local login enabled.
/// It takes two arguments — a Google OAuth **client id** and **client secret**. Because the login uses a loopback redirect, the client must be a **Desktop
/// app** client (the only type Google permits a loopback redirect for — *not* the production iOS/Android mobile client the app itself uses). Google
/// requires the client secret on the token exchange for a Desktop client, even though PKCE is also used, so both values are needed here.
///
/// Obtaining the Desktop client (one-off, in the Google Cloud Console, on the project that owns the Calendar integration):
///
/// 1. APIs & Services → Library → enable **Google Calendar API**.
/// 2. APIs & Services → OAuth consent screen → set the app name and support email; under Data Access add the scope `.../auth/calendar.readonly`; under
/// Audience add your own Google account as a **Test user** (this sensitive scope needs Google verification for public use, but a test user can consent now).
/// 3. APIs & Services → Credentials → **Create credentials → OAuth client ID** → Application type **Desktop app** → Create.
/// 4. Copy the **Client ID** (`NNNNNNNNN-xxxx.apps.googleusercontent.com`) and the **client secret** shown alongside it.
///
/// Running:
///
/// 1. Launch this class with the Desktop client id and client secret as the two program arguments.
/// 2. Open the authorization URL it logs in a browser, sign in, and approve the calendar access.
/// 3. The loopback redirect completes the token exchange; the runner then lists your calendars and each one's next-day events, then exits.
final class LocalGoogleCalendarRunner {
    private static final Logger logger = LogManager.getLogger(LocalGoogleCalendarRunner.class);

    private static final String KEYCHAIN_ITEM = "automator-keystore";

    private LocalGoogleCalendarRunner() {
    }

    static void main(String[] args) {
        checkArgument(args.length >= 2, "usage: pass the Desktop OAuth client id and client secret as the two arguments");
        Application.builder()
                   .addModule(() -> TimeModule.builder().build())
                   .addModule(() -> ExecutorModule.builder().build())
                   .addModule(() -> GoogleCalendarModule
                           .builder()
                           .setClientId(literally(args[0]))
                           .withClientSecret(literally(args[1]))
                           .setRedirectUri(literally("unused"))
                           .withLocalLogin(true)
                           .withVarStore(exposedBy(
                                   VarStoreModule
                                           .builder()
                                           .withSingleUser(literally(true))
                                           .withPath(literally(Paths.get(System.getProperty("user.home"),
                                                                         ".jiotty",
                                                                         LocalGoogleCalendarRunner.class.getSimpleName())))
                                           .withKeyStoreAccess(exposedBy(KeyStoreAccessModule.builder()
                                                                                             .setPathToKeystore(literally(Paths.get(
                                                                                                     "/Volumes/secrets/secrets.p12")))
                                                                                             .setKeystorePass(literally(keystorePassFromKeychain()))
                                                                                             .build()))
                                           .withEncryptionKeyAlias(literally("varstore-master-key"))
                                           .build()))
                           .build())
                   .addModule(() -> new BaseLifecycleComponentModule() {
                       @Override
                       protected void configure() {
                           registerLifecycleComponent(Runner.class);
                       }
                   })
                   .build()
                   .run();
    }

    private static String keystorePassFromKeychain() {
        return getAsUnchecked(() -> {
            Process process = new ProcessBuilder("/usr/bin/security", "find-generic-password", "-s", KEYCHAIN_ITEM, "-w").start();
            String password;
            try (BufferedReader reader = process.inputReader(UTF_8)) {
                password = reader.readLine();
            }
            int exitCode = process.waitFor();
            checkState(exitCode == 0,
                       "`security find-generic-password -s %s -w` exited with %s; add the item with `security add-generic-password -a $USER -s %s -w`",
                       KEYCHAIN_ITEM, exitCode, KEYCHAIN_ITEM);
            return checkNotNull(password, "macOS keychain returned no password for item '%s'", KEYCHAIN_ITEM);
        });
    }


    @SuppressWarnings("CallToSystemExit") // a manual runner: exit the JVM once the one-shot listing completes
    static final class Runner extends BaseLifecycleComponent {
        private final CalendarService service;
        private final AtomicBoolean started = new AtomicBoolean();
        private @Nullable Closeable authSubscription;

        @Inject
        Runner(CalendarService service) {
            this.service = checkNotNull(service);
        }

        @Override
        protected void doStart() {
            // The local login is asynchronous (it waits for the browser sign-in), so start listing only once the first successful token arrives.
            authSubscription = service.subscribeToAuthState(authState -> {
                if (authState instanceof AuthState.Success && started.compareAndSet(false, true)) {
                    listCalendarsAndEvents();
                }
            });
        }

        @Override
        protected void doStop() {
            Closeable.closeSafelyIfNotNull(logger, authSubscription);
            authSubscription = null;
        }

        private void listCalendarsAndEvents() {
            service.retrieveCalendars()
                   .thenAccept(result -> {
                       // this runner only lists once the auth state is Success, so the result always carries the calendar list
                       List<Calendar> calendars = ((Calendars) result).calendars();
                       logger.info("Calendars: {}", calendars.stream().map(Calendar::name).toList());
                       calendars.stream()
                                .map(calendar -> calendar.fetchEvents(Instant.now(), Instant.now().plus(1, ChronoUnit.DAYS))
                                                         .thenAccept(events -> {
                                                             logger.info("*** CALENDAR: {}, events: {}", calendar, events.size());
                                                             events.forEach(event -> logger.info("****** EVENT: {}", event));
                                                         }))
                                .collect(CompletableFutures.toFutureOfList())
                                .whenComplete((_, throwable) -> {
                                    logger.log(throwable == null ? Level.INFO : Level.ERROR, "Completed", throwable);
                                    var exitThread = new Thread(() -> System.exit(0));
                                    exitThread.setDaemon(true);
                                    exitThread.start();
                                });
                   })
                   .whenComplete((_, throwable) -> {
                       if (throwable != null) {
                           logger.error("Failed to retrieve calendars", throwable);
                       }
                   });
        }
    }
}

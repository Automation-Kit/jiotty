package net.yudichev.jiotty.connector.icloud.calendar;

import jakarta.inject.Inject;
import net.yudichev.jiotty.common.app.Application;
import net.yudichev.jiotty.common.async.ExecutorModule;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.lang.CompletableFutures;
import net.yudichev.jiotty.common.time.calendar.Calendar;
import net.yudichev.jiotty.common.time.calendar.CalendarService;
import net.yudichev.jiotty.common.time.calendar.CalendarService.CalendarsResult.Calendars;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static net.yudichev.jiotty.common.inject.BindingSpec.literally;

final class LocalIcloudCalendarRunner {
    private static final Logger logger = LogManager.getLogger(LocalIcloudCalendarRunner.class);

    static void main(String[] args) {
        Application.builder()
                   .addModule(ExecutorModule::new)
                   .addModule(() -> IcloudCalendarModule.builder()
                                                        .setUsername(literally(args[0]))
                                                        .setPassword(literally(args[1]))
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

    @SuppressWarnings("CallToSystemExit")
    static class Runner extends BaseLifecycleComponent {

        private final CalendarService service;

        @Inject
        public Runner(CalendarService service) {
            this.service = service;
        }

        @Override
        protected void doStart() {
            service.retrieveCalendars().thenAccept(result -> {
                       // the iCloud service authenticates on the retrieval call itself, so the result always carries the calendar list
                       List<Calendar> calendars = ((Calendars) result).calendars();
                       logger.info("Calendars: {}", calendars.stream().map(Calendar::name).toList());
                       calendars.stream()
                                .map(calendar -> calendar.fetchEvents(Instant.now(), Instant.now().plus(1, ChronoUnit.DAYS))
                                                         .thenAccept(calendarEvents -> {
                                                             logger.info("*** CALENDAR: {}, events: {}", calendar, calendarEvents.size());
                                                             calendarEvents.forEach(event -> logger.info("****** EVENT: {}", event));
                                                         }))
                                .collect(CompletableFutures.toFutureOfList())
                                .whenComplete((r, e) -> {
                                    logger.info("Completed: {}", r, e);
                                    var thread = new Thread(() -> System.exit(0));
                                    thread.setDaemon(true);
                                    thread.start();
                                });
                   })
                   .whenComplete((_, throwable) -> logger.log(throwable == null ? Level.INFO : Level.ERROR, "Result", throwable));
        }
    }
}

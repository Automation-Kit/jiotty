package net.yudichev.jiotty.user.ui;

import net.yudichev.jiotty.adminalerts.AdminAlertService;
import net.yudichev.jiotty.user.ui.io.Deadline;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.util.function.Consumer;

import static net.yudichev.jiotty.adminalerts.AdminAlertSeverity.WARNING;

/// The deadline of the API handlers that answer their own requests.
final class HandlerDeadlines {
    /// As long as the app waits for any answer: past it, no client reads the answer.
    static final Duration TIMEOUT = Duration.ofSeconds(30);

    private HandlerDeadlines() {
    }

    /// Creates a deadline answering [ErrorResponse#SERVER_FAULT] after [#TIMEOUT], with a `timedOutAlertTitle` alert for a handler that has not answered by
    /// then. A request whose body was still arriving is logged at debug, because any caller can trickle its body.
    ///
    /// @param subjectId         the user whose requests the deadline bounds, prefixing its alert and log line, which run on a container thread that no
    ///                          user's thread name identifies
    /// @param onEncodingFailure invoked on a container thread with the failure when the deadline's answer cannot be encoded
    static Deadline create(AdminAlertService alertService,
                           Logger logger,
                           String subjectId,
                           String timedOutAlertTitle,
                           Consumer<? super Exception> onEncodingFailure) {
        String alertDescription = '[' + subjectId + "] No answer within " + TIMEOUT;
        return Deadline.after(TIMEOUT, ErrorResponse.SERVER_FAULT, onEncodingFailure, stall -> {
            if (stall.serverStalled()) {
                alertService.raise(WARNING, timedOutAlertTitle, alertDescription);
            } else {
                logger.debug("[{}] {}: the request body was still arriving after {}", subjectId, timedOutAlertTitle, TIMEOUT);
            }
        });
    }
}

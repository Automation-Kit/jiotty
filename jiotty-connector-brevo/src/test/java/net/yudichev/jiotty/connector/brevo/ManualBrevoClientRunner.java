package net.yudichev.jiotty.connector.brevo;

import com.google.inject.Module;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.app.Application;
import net.yudichev.jiotty.common.async.ExecutorModule;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;

/// Exercises [BrevoClientModule] against the real Brevo API by hand: `<apiKey> <senderAddress> <senderName> <recipientAddress> [recipientName]` sends one
/// message, and {@value #CHECK_COMMAND} calls [BrevoClient#checkAccess()] with the key read from the {@value #API_KEY_VARIABLE} environment variable,
/// which keeps the key off the command line and so out of the process list. Omitting the recipient name exercises the absent-name path, which Brevo
/// rejects if it is ever serialised as an explicit null.
// A hand-run main with no logger and no test harness: the console is its only output, a non-zero exit is how it reports a rejected call, and the
// environment is where the check form reads its key.
@SuppressWarnings({"UseOfSystemOutOrSystemErr", "CallToSystemExit", "CallToSystemGetenv"})
final class ManualBrevoClientRunner {
    private static final String CHECK_COMMAND = "check";
    private static final String API_KEY_VARIABLE = "BREVO_API_KEY";

    static void main(String[] args) {
        if (args.length == 1 && CHECK_COMMAND.equals(args[0])) {
            run(apiKeyFromEnvironment(), new BaseLifecycleComponentModule() {
                @Override
                protected void configure() {
                    registerLifecycleComponent(AccessCheckRunner.class);
                }
            });
        } else if (args.length >= 4) {
            BrevoEmail email = BrevoEmail.builder()
                                         .setSenderAddress(args[1])
                                         .setSenderName(args[2])
                                         .setRecipientAddress(args[3])
                                         .setRecipientName(args.length > 4 ? args[4] : null)
                                         .setSubject("Brevo connector test " + Instant.now())
                                         .setHtmlContent(htmlContent())
                                         .setTextContent(textContent())
                                         .build();
            run(args[0], new BaseLifecycleComponentModule() {
                @Override
                protected void configure() {
                    bind(BrevoEmail.class).toInstance(email);
                    registerLifecycleComponent(SendRunner.class);
                }
            });
        } else {
            System.err.printf("""
                              Usage: ManualBrevoClientRunner <apiKey> <senderAddress> <senderName> <recipientAddress> [recipientName]
                                     %s=<apiKey> ManualBrevoClientRunner %s
                              """, API_KEY_VARIABLE, CHECK_COMMAND);
            System.exit(2);
        }
    }

    private static String apiKeyFromEnvironment() {
        String apiKey = System.getenv(API_KEY_VARIABLE);
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println(API_KEY_VARIABLE + " is not set");
            System.exit(2);
        }
        return apiKey;
    }

    private static void run(String apiKey, Module runnerModule) {
        Application.builder()
                   .addModule(() -> ExecutorModule.builder().build())
                   .addModule(() -> BrevoClientModule.builder()
                                                     .setApiKey(literally(apiKey))
                                                     .build())
                   .addModule(() -> runnerModule)
                   .build()
                   .run();
    }

    /// Carries markup and a non-ASCII character, so what lands in the inbox says whether the body survived transport as HTML and as UTF-8.
    private static String htmlContent() {
        return """
               <p style="margin:0 0 16px;">This is a <strong>test message</strong> from the Brevo connector's manual runner.</p>
               <p style="margin:0;">Reading this in an inbox means the key, the sender domain's SPF and DKIM records and the wire shape all work — £.</p>\
               """;
    }

    /// The alternative a text-only client shows. Deliberately different wording from the HTML, so the one you are looking at is unambiguous.
    private static String textContent() {
        return """
               This is the PLAIN TEXT alternative from the Brevo connector's manual runner.
               
               Seeing this rather than the HTML means your client chose the text part — £.""";
    }

    /// Logs the outcome of `call` and exits the process with 0 when it completed with `true`, 1 otherwise.
    private static void exitWhenComplete(CompletableFuture<Boolean> call, Logger logger, String acceptedMessage, String rejectedMessage) {
        call.whenComplete((accepted, error) -> {
            boolean succeeded = error == null && accepted;
            if (succeeded) {
                logger.info(acceptedMessage);
            } else {
                logger.error(rejectedMessage, error);
            }
            var thread = new Thread(() -> System.exit(succeeded ? 0 : 1));
            thread.setDaemon(true);
            thread.start();
        });
    }

    private static final class SendRunner extends BaseLifecycleComponent {
        private static final Logger logger = LogManager.getLogger(SendRunner.class);

        private final BrevoClient client;
        private final BrevoEmail email;

        @Inject
        SendRunner(BrevoClient client, BrevoEmail email) {
            this.client = checkNotNull(client);
            this.email = checkNotNull(email);
        }

        @Override
        protected void doStart() {
            // The recipient is redacted in this line, as it is everywhere else: seeing the mask here is itself part of what the run verifies.
            logger.info("Sending {}", email);
            exitWhenComplete(client.sendEmail(email).thenApply(_ -> true),
                             logger,
                             "Brevo accepted the message; check the inbox (and the spam folder)",
                             "Brevo rejected the message");
        }
    }

    private static final class AccessCheckRunner extends BaseLifecycleComponent {
        private static final Logger logger = LogManager.getLogger(AccessCheckRunner.class);

        private final BrevoClient client;

        @Inject
        AccessCheckRunner(BrevoClient client) {
            this.client = checkNotNull(client);
        }

        @Override
        protected void doStart() {
            exitWhenComplete(client.checkAccess().thenApply(result -> switch (result) {
                                 case ACCEPTED -> true;
                                 case KEY_REJECTED -> false;
                             }),
                             logger,
                             "Brevo accepted the key",
                             "Brevo rejected the key");
        }
    }
}

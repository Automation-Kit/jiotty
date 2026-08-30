package net.yudichev.jiotty.connector.brevo;

import jakarta.inject.Inject;
import net.yudichev.jiotty.common.app.Application;
import net.yudichev.jiotty.common.async.ExecutorModule;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;

/// Sends one real message through Brevo, for exercising [BrevoClientModule] by hand.
///
/// Usage: `ManualBrevoClientRunner <apiKey> <senderAddress> <senderName> <recipientAddress> [recipientName]` — omitting the recipient name exercises the
/// absent-name path, which Brevo rejects if it is ever serialised as an explicit null.
// A hand-run main with no logger and no test harness: the console is its only output, and a non-zero exit is how it reports a refused send.
@SuppressWarnings({"UseOfSystemOutOrSystemErr", "CallToSystemExit"})
final class ManualBrevoClientRunner {
    static void main(String[] args) {
        if (args.length < 4) {
            System.err.println("Usage: ManualBrevoClientRunner <apiKey> <senderAddress> <senderName> <recipientAddress> [recipientName]");
            System.exit(2);
        }
        String apiKey = args[0];
        BrevoEmail email = BrevoEmail.builder()
                                     .setSenderAddress(args[1])
                                     .setSenderName(args[2])
                                     .setRecipientAddress(args[3])
                                     .setRecipientName(args.length > 4 ? args[4] : null)
                                     .setSubject("Brevo connector test " + Instant.now())
                                     .setHtmlContent(htmlContent())
                                     .setTextContent(textContent())
                                     .build();
        Application.builder()
                   .addModule(() -> ExecutorModule.builder().build())
                   .addModule(() -> BrevoClientModule.builder()
                                                     .setApiKey(literally(apiKey))
                                                     .build())
                   .addModule(() -> new BaseLifecycleComponentModule() {
                       @Override
                       protected void configure() {
                           bind(BrevoEmail.class).toInstance(email);
                           registerLifecycleComponent(Runner.class);
                       }
                   })
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

    private static final class Runner extends BaseLifecycleComponent {
        private static final Logger logger = LogManager.getLogger(Runner.class);

        private final BrevoClient client;
        private final BrevoEmail email;

        @Inject
        Runner(BrevoClient client, BrevoEmail email) {
            this.client = checkNotNull(client);
            this.email = checkNotNull(email);
        }

        @Override
        protected void doStart() {
            // The recipient is redacted in this line, as it is everywhere else: seeing the mask here is itself part of what the run verifies.
            logger.info("Sending {}", email);
            client.sendEmail(email)
                  .whenComplete((_, error) -> {
                      if (error == null) {
                          logger.info("Brevo accepted the message; check the inbox (and the spam folder)");
                      } else {
                          logger.error("Brevo rejected the message", error);
                      }
                      var thread = new Thread(() -> System.exit(error == null ? 0 : 1));
                      thread.setDaemon(true);
                      thread.start();
                  });
        }
    }
}

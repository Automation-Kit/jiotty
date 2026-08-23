package net.yudichev.jiotty.connector.anthropic;

import com.google.inject.BindingAnnotation;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.app.Application;
import net.yudichev.jiotty.common.app.ApplicationLifecycleControl;
import net.yudichev.jiotty.common.async.ExecutorModule;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.MINUTES;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;

/// Interactive console chat against the real Anthropic API, for exercising [AnthropicClientModule] by hand.
///
/// Usage: `AnthropicChatLocalRunner <apiKey> [model]` — type a message, press enter, read the reply. Every turn replays the whole exchange, so the model
/// keeps context. `exit` (or end-of-input) shuts the application down.
final class AnthropicChatLocalRunner {
    private static final String DEFAULT_MODEL = "claude-haiku-4-5";

    static void main(String[] args) {
        checkArgument(args.length >= 1, "usage: <apiKey> [model], where model defaults to %s", DEFAULT_MODEL);
        var apiKey = args[0];
        var model = args.length > 1 ? args[1] : DEFAULT_MODEL;
        Application.builder()
                   .addModule(() -> ExecutorModule.builder().build())
                   .addModule(() -> AnthropicClientModule.builder()
                                                         .setApiKey(literally(apiKey))
                                                         .build())
                   .addModule(() -> new BaseLifecycleComponentModule() {
                       @Override
                       protected void configure() {
                           bindConstant().annotatedWith(Chat.Model.class).to(model);
                           registerLifecycleComponent(Chat.class);
                       }
                   })
                   .build()
                   .run();
    }

    /// Reads from stdin on its own thread, because [#doStart] must return for the application to finish starting.
    // Suppressed for the whole class: the console IS this runner's interface, so every print here is deliberate, and a stack trace on the terminal is what an
    // operator driving it by hand needs to see.
    @SuppressWarnings({"UseOfSystemOutOrSystemErr", "CallToPrintStackTrace"})
    static final class Chat extends BaseLifecycleComponent {
        /// Big enough for a conversational answer without letting a runaway reply burn the console budget.
        private static final int MAX_TOKENS = 1024;

        private final AnthropicClient client;
        private final String model;
        private final ApplicationLifecycleControl lifecycleControl;
        /// The whole exchange so far, replayed on every call — the API holds no conversation state of its own.
        private final List<Message> conversation = new ArrayList<>();

        private Thread thread;

        @Inject
        Chat(AnthropicClient client, @Model String model, ApplicationLifecycleControl lifecycleControl) {
            this.client = checkNotNull(client);
            this.model = checkNotNull(model);
            this.lifecycleControl = checkNotNull(lifecycleControl);
        }

        @Override
        protected void doStart() {
            thread = new Thread(this::chat, "anthropic-chat");
            thread.start();
        }

        @Override
        protected void doStop() {
            if (thread != null) {
                thread.interrupt();
            }
        }

        private void chat() {
            var reader = new BufferedReader(new InputStreamReader(System.in, UTF_8));
            System.out.printf("Chatting with %s. Type 'exit' to quit.%n", model);
            while (true) {
                System.out.print("> ");
                var line = getAsUnchecked(reader::readLine);
                if (line == null || "exit".equalsIgnoreCase(line.strip())) {
                    break;
                }
                if (!line.isBlank()) {
                    try {
                        say(line);
                    } catch (RuntimeException e) {
                        // Printed rather than rethrown: one failed turn should not end the session, and the stack trace is the point of a manual runner.
                        e.printStackTrace();
                    }
                }
            }
            lifecycleControl.initiateShutdown();
        }

        private void say(String line) {
            conversation.add(Messages.createUserText(line));
            var response = getAsUnchecked(() -> client.sendMessage(MessagesRequest.builder()
                                                                                  .setModel(model)
                                                                                  .setMaxTokens(MAX_TOKENS)
                                                                                  .addAllMessages(conversation)
                                                                                  .build())
                                                      .get(2, MINUTES));
            // Appended straight to stdout: PrintStream is an Appendable, so the reply reaches the console without being assembled into a String first.
            response.appendText(System.out);
            if (!response.isCompleteTurn()) {
                System.out.printf("%n[reply did not finish: stop_reason=%s]", response.stopReason().orElse("absent"));
            }
            var usage = response.usage();
            System.out.printf("%n[in=%d out=%d cache_read=%d cache_write=%d]%n",
                              usage.inputTokens(),
                              usage.outputTokens(),
                              usage.cacheReadInputTokens(),
                              usage.cacheCreationInputTokens());
            // The reply joins the history as the assistant's turn, which is what lets the next question refer back to it. Its blocks go back exactly as they
            // arrived, which is also what a tool-using conversation requires.
            conversation.add(Message.of(Role.ASSISTANT, response.content()));
        }

        @BindingAnnotation
        @Target({FIELD, PARAMETER, METHOD})
        @Retention(RUNTIME)
        @interface Model {}
    }
}

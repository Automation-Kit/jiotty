package net.yudichev.jiotty.persistence.recording;

import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.common.lang.Appender;
import net.yudichev.jiotty.common.time.DateTimeUtils;
import net.yudichev.jiotty.user.ui.TextFormat;

import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

public sealed interface UIDestination extends Destination permits UIDestinationImpl {
    interface HtmlRenderer<R> {
        default void initialise(DateTimeUtils.Formatter dateTimeFormatter) {
        }

        void render(R recordable, Appender target);
    }

    record UIConfig<R>(Class<R> recordType,
                       String title,
                       TextFormat textFormat,
                       int windowSize,
                       Function<R, String> displayableEventKeyExtractor,
                       Supplier<HtmlRenderer<R>> renderer,
                       BiConsumer<String, HttpServletResponse> downloadHandler)
            implements Destination.Config<R> {
        public UIConfig(Class<R> recordType,
                        String title,
                        TextFormat textFormat,
                        int windowSize,
                        Function<R, String> displayableEventKeyExtractor,
                        Supplier<HtmlRenderer<R>> renderer) {
            this(recordType, title, textFormat, windowSize, displayableEventKeyExtractor, renderer, (ignored1, ignored2) -> {});
        }

        @Override
        public DestinationType destinationType() {
            return DestinationType.UI;
        }
    }
}

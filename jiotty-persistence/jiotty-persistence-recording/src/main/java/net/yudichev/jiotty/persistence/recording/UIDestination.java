package net.yudichev.jiotty.persistence.recording;

import jakarta.annotation.Nullable;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.common.lang.Appender;
import net.yudichev.jiotty.common.time.DateTimeUtils;
import net.yudichev.jiotty.user.ui.HistoryDisplayableDto;
import net.yudichev.jiotty.user.ui.UIServer;

import java.time.ZoneId;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import static net.yudichev.jiotty.common.lang.CompletableFutures.completedFuture;

public sealed interface UIDestination extends Destination permits UIDestinationImpl {
    interface HtmlRenderer<R> {
        default void initialise(DateTimeUtils.Formatter dateTimeFormatter) {
        }

        void render(R recordable, Appender target);
    }

    record UIConfig<R>(UIServer uiServer,
                       ZoneId zoneId,
                       Class<R> recordType,
                       String title,
                       HistoryDisplayableDto.Format format,
                       int windowSize,
                       Function<R, String> displayableEventKeyExtractor,
                       @Nullable Supplier<HtmlRenderer<R>> renderer,
                       BiFunction<String, HttpServletResponse, CompletableFuture<Void>> downloadHandler)
            implements Destination.Config<R> {
        public UIConfig(UIServer uiServer,
                        ZoneId zoneId,
                        Class<R> recordType,
                        String title,
                        HistoryDisplayableDto.Format format,
                        int windowSize,
                        Function<R, String> displayableEventKeyExtractor,
                        @Nullable Supplier<HtmlRenderer<R>> renderer) {
            this(uiServer, zoneId, recordType, title, format, windowSize, displayableEventKeyExtractor, renderer, (_, _) -> completedFuture());
        }

        @Override
        public DestinationType destinationType() {
            return DestinationType.UI;
        }
    }
}

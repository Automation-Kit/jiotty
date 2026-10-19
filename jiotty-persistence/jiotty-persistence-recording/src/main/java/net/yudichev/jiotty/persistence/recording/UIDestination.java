package net.yudichev.jiotty.persistence.recording;

import net.yudichev.jiotty.common.time.DateTimeUtils;
import net.yudichev.jiotty.user.ui.Displayable;
import net.yudichev.jiotty.user.ui.HistoryDisplayableDto;
import net.yudichev.jiotty.user.ui.UIServer;
import net.yudichev.jiotty.user.ui.io.Answer;
import org.jspecify.annotations.Nullable;

import java.time.ZoneId;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

import static java.util.concurrent.CompletableFuture.completedFuture;

public sealed interface UIDestination extends Destination permits UIDestinationImpl {
    interface HtmlRenderer<R> {
        default void initialise(DateTimeUtils.Formatter dateTimeFormatter) {
        }

        void render(R recordable, Appendable appendable);
    }

    record UIConfig<R>(UIServer uiServer,
                       ZoneId zoneId,
                       Class<R> recordType,
                       String title,
                       HistoryDisplayableDto.Format format,
                       int windowSize,
                       Function<R, String> displayableEventKeyExtractor,
                       @Nullable Supplier<HtmlRenderer<R>> renderer,
                       Function<String, CompletableFuture<Answer>> downloadHandler)
            implements Destination.Config<R> {
        public UIConfig(UIServer uiServer,
                        ZoneId zoneId,
                        Class<R> recordType,
                        String title,
                        HistoryDisplayableDto.Format format,
                        int windowSize,
                        Function<R, String> displayableEventKeyExtractor,
                        @Nullable Supplier<HtmlRenderer<R>> renderer) {
            this(uiServer,
                 zoneId,
                 recordType,
                 title,
                 format,
                 windowSize,
                 displayableEventKeyExtractor,
                 renderer,
                 _ -> completedFuture(Displayable.NO_DOWNLOAD));
        }

        @Override
        public DestinationType destinationType() {
            return DestinationType.UI;
        }
    }
}

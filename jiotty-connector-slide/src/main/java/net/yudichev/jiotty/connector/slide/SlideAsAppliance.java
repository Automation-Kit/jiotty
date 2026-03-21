package net.yudichev.jiotty.connector.slide;

import com.google.common.collect.ImmutableSet;
import com.google.inject.BindingAnnotation;
import jakarta.inject.Inject;
import net.yudichev.jiotty.appliance.Appliance;
import net.yudichev.jiotty.appliance.Command;
import net.yudichev.jiotty.appliance.CommandMeta;
import net.yudichev.jiotty.appliance.PowerCommand;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static net.yudichev.jiotty.appliance.PowerCommand.ON;
import static net.yudichev.jiotty.connector.slide.Bindings.SlideId;
import static net.yudichev.jiotty.connector.slide.SetCurtainPositionCommand.allSetCurtainPositionCommandMetas;

final class SlideAsAppliance implements Appliance {
    private static final Logger logger = LogManager.getLogger(SlideAsAppliance.class);

    private static final ImmutableSet<CommandMeta<?>> SUPPORTED_COMMANDS = ImmutableSet.<CommandMeta<?>>builder()
                                                                                       .addAll(PowerCommand.allPowerCommandMetas())
                                                                                       .addAll(allSetCurtainPositionCommandMetas())
                                                                                       .build();

    private final SlideService slideService;
    private final long slideId;
    private final String name;

    @Inject
    SlideAsAppliance(@Dependency SlideService slideService,
                     @SlideId long slideId,
                     @Name String name) {
        this.slideService = checkNotNull(slideService);
        this.slideId = slideId;
        this.name = checkNotNull(name);
    }

    @Override
    public CompletableFuture<?> execute(Command<?> command) {
        return command.accept((PowerCommand.Visitor<CompletableFuture<?>>) powerCommand -> setPosition(powerCommand == ON ? 1.0 : 0.0))
                      .orElseGet(() -> command.acceptOrFail((SetCurtainPositionCommand.Visitor<CompletableFuture<?>>) posCommand -> setPosition(posCommand.getPosition())))
                      .thenRun(() -> logger.info("Slide {}: executed {}", name, command));
    }

    private CompletableFuture<Void> setPosition(double targetPosition) {
        return slideService.setSlidePosition(slideId, targetPosition);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Set<CommandMeta<?>> getAllSupportedCommandMetadata() {
        return SUPPORTED_COMMANDS;
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Dependency {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Name {
    }
}

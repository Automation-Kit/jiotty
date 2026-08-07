package net.yudichev.jiotty.connector.rpigpio;

import com.pi4j.io.gpio.digital.PullResistance;
import net.yudichev.jiotty.common.inject.BaseExposedKeyModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;

import static com.google.common.base.Preconditions.checkNotNull;

public final class RpiDigitalPinStatusMonitorModule extends BaseExposedKeyModule<RpiDigitalPinStatusMonitor> {
    private final Integer pin;
    private final PullResistance pullResistance;

    private RpiDigitalPinStatusMonitorModule(int pin, PullResistance pullResistance, SpecifiedAnnotation specifiedAnnotation) {
        super(specifiedAnnotation);
        this.pin = pin;
        this.pullResistance = checkNotNull(pullResistance);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected void configure() {
        bind(Integer.class).annotatedWith(RpiDigitalPinStatusMonitorImpl.Pin.class).toInstance(pin);
        bind(PullResistance.class).annotatedWith(RpiDigitalPinStatusMonitorImpl.Dependency.class).toInstance(pullResistance);
        bind(exposedKey).to(registerLifecycleComponent(RpiDigitalPinStatusMonitorImpl.class));
        expose(exposedKey);
    }

    public static class Builder extends BaseModuleBuilder<RpiDigitalPinStatusMonitor, Builder> {
        private int pin;
        private PullResistance pullResistance;

        public Builder setPin(int pin) {
            this.pin = pin;
            return this;
        }

        public Builder setPullResistance(PullResistance pullResistance) {
            this.pullResistance = checkNotNull(pullResistance);
            return this;
        }

        @Override
        public ExposedKeyModule<RpiDigitalPinStatusMonitor> build() {
            return new RpiDigitalPinStatusMonitorModule(pin, pullResistance, specifiedAnnotation());
        }
    }
}

package net.yudichev.jiotty.connector.icloud.calendar;

import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.lang.TypedBuilder;
import net.yudichev.jiotty.common.time.calendar.CalendarService;

import static com.google.common.base.Preconditions.checkNotNull;

public final class IcloudCalendarModule extends BaseLifecycleComponentModule implements ExposedKeyModule<CalendarService> {
    private final BindingSpec<String> usernameSpec;
    private final BindingSpec<String> passwordSpec;

    public IcloudCalendarModule(BindingSpec<String> usernameSpec, BindingSpec<String> passwordSpec) {
        this.usernameSpec = checkNotNull(usernameSpec);
        this.passwordSpec = checkNotNull(passwordSpec);
    }

    @Override
    protected void configure() {
        usernameSpec.bind(String.class).annotatedWith(IcloudCalendarService.Username.class).installedBy(this::installLifecycleComponentModule);
        passwordSpec.bind(String.class).annotatedWith(IcloudCalendarService.Password.class).installedBy(this::installLifecycleComponentModule);

        bind(getExposedKey()).to(registerLifecycleComponent(IcloudCalendarService.class));
        expose(getExposedKey());
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder implements TypedBuilder<ExposedKeyModule<CalendarService>> {
        private BindingSpec<String> usernameSpec;
        private BindingSpec<String> passwordSpec;

        public Builder setUsername(BindingSpec<String> usernameSpec) {
            this.usernameSpec = checkNotNull(usernameSpec);
            return this;
        }

        public Builder setPassword(BindingSpec<String> passwordSpec) {
            this.passwordSpec = checkNotNull(passwordSpec);
            return this;
        }

        @Override
        public ExposedKeyModule<CalendarService> build() {
            return new IcloudCalendarModule(usernameSpec, passwordSpec);
        }
    }
}

package net.yudichev.jiotty.connector.icloud.calendar;

import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.inject.BaseExposedKeyModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;
import net.yudichev.jiotty.common.time.calendar.CalendarService;

import java.time.Duration;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;

public final class IcloudCalendarModule extends BaseExposedKeyModule<CalendarService> {
    private final BindingSpec<String> usernameSpec;
    private final BindingSpec<String> passwordSpec;
    private final BindingSpec<String> logSubjectIdSpec;
    private final BindingSpec<SchedulingExecutor> executorSpec;
    private final BindingSpec<Duration> timeoutSpec;

    private IcloudCalendarModule(BindingSpec<String> usernameSpec,
                                 BindingSpec<String> passwordSpec,
                                 BindingSpec<String> logSubjectIdSpec,
                                 BindingSpec<SchedulingExecutor> executorSpec,
                                 BindingSpec<Duration> timeoutSpec,
                                 SpecifiedAnnotation specifiedAnnotation) {
        super(specifiedAnnotation);
        this.usernameSpec = checkNotNull(usernameSpec);
        this.passwordSpec = checkNotNull(passwordSpec);
        this.logSubjectIdSpec = checkNotNull(logSubjectIdSpec);
        this.executorSpec = checkNotNull(executorSpec);
        this.timeoutSpec = checkNotNull(timeoutSpec);
    }

    @Override
    protected void configure() {
        usernameSpec.bind(String.class).annotatedWith(IcloudCalendarService.Username.class).installedBy(this::installLifecycleComponentModule);
        passwordSpec.bind(String.class).annotatedWith(IcloudCalendarService.Password.class).installedBy(this::installLifecycleComponentModule);
        logSubjectIdSpec.bind(String.class).annotatedWith(IcloudCalendarService.LogSubjectId.class).installedBy(this::installLifecycleComponentModule);
        executorSpec.bind(SchedulingExecutor.class).annotatedWith(IcloudCalendarService.Dependency.class).installedBy(this::installLifecycleComponentModule);
        timeoutSpec.bind(Duration.class).annotatedWith(IcloudCalendarService.Timeout.class).installedBy(this::installLifecycleComponentModule);

        bind(exposedKey).to(registerLifecycleComponent(IcloudCalendarService.class));
        expose(exposedKey);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder extends BaseModuleBuilder<CalendarService, Builder> {
        private BindingSpec<String> usernameSpec;
        private BindingSpec<String> passwordSpec;
        private BindingSpec<String> logSubjectIdSpec = literally("");
        private BindingSpec<Duration> timeoutSpec = literally(Duration.ofSeconds(30));
        private BindingSpec<SchedulingExecutor> executorSpec;

        public Builder setUsername(BindingSpec<String> usernameSpec) {
            this.usernameSpec = checkNotNull(usernameSpec);
            return this;
        }

        public Builder setPassword(BindingSpec<String> passwordSpec) {
            this.passwordSpec = checkNotNull(passwordSpec);
            return this;
        }

        /// A GDPR-safe subject id (e.g. the internal user id) that prefixes this instance's log lines as `[{}]`, so concurrent instances with different
        /// subject ids stay distinguishable in a shared log. Defaults to empty (single-instance use).
        public Builder withLogSubjectId(BindingSpec<String> logSubjectIdSpec) {
            this.logSubjectIdSpec = checkNotNull(logSubjectIdSpec);
            return this;
        }

        /// Orders this service's calendar-list retrievals on the specified executor, which the service never blocks.
        public Builder setExecutor(BindingSpec<SchedulingExecutor> executorSpec) {
            this.executorSpec = checkNotNull(executorSpec);
            return this;
        }

        /// The connect, read, write and whole-call timeout applied to every CalDAV request; defaults to 30 seconds.
        public Builder withTimeout(BindingSpec<Duration> timeoutSpec) {
            this.timeoutSpec = checkNotNull(timeoutSpec);
            return this;
        }

        @Override
        public ExposedKeyModule<CalendarService> build() {
            return new IcloudCalendarModule(usernameSpec, passwordSpec, logSubjectIdSpec, executorSpec, timeoutSpec, specifiedAnnotation());
        }
    }
}

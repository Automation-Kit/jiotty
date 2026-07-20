package net.yudichev.jiotty.connector.icloud.calendar;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.reflect.TypeToken;
import com.google.inject.Key;
import net.yudichev.jiotty.common.async.ExecutorProviderModule;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;
import net.yudichev.jiotty.common.time.calendar.CalendarService;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.inject.BindingSpec.exposedBy;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.inject.GuiceUtil.uniqueAnnotation;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;

public final class IcloudCalendarModule extends BaseLifecycleComponentModule implements ExposedKeyModule<CalendarService> {
    private static final String BASE_EXECUTOR_NAME = "Icloud-Calendar";

    private final BindingSpec<String> usernameSpec;
    private final BindingSpec<String> passwordSpec;
    private final BindingSpec<String> logSubjectIdSpec;
    private final BindingSpec<SchedulingExecutor> executorSpec;
    private final Key<CalendarService> exposedKey;

    private IcloudCalendarModule(BindingSpec<String> usernameSpec,
                                 BindingSpec<String> passwordSpec,
                                 BindingSpec<String> logSubjectIdSpec,
                                 BindingSpec<SchedulingExecutor> executorSpec,
                                 SpecifiedAnnotation specifiedAnnotation) {
        this.usernameSpec = checkNotNull(usernameSpec);
        this.passwordSpec = checkNotNull(passwordSpec);
        this.logSubjectIdSpec = checkNotNull(logSubjectIdSpec);
        this.executorSpec = checkNotNull(executorSpec);
        exposedKey = specifiedAnnotation.specify(ExposedKeyModule.super.getExposedKey().getTypeLiteral());
    }

    /// The executor thread name: tags the thread with the supplied subject id so `[%t]` in the log pattern and the executor's `name` metric label distinguish
    /// concurrent per-user instances. Falls back to a bare {@value #BASE_EXECUTOR_NAME} when no subject id is supplied.
    @VisibleForTesting
    static String threadName(String logSubjectId) {
        return logSubjectId.isBlank() ? BASE_EXECUTOR_NAME : BASE_EXECUTOR_NAME + '-' + logSubjectId;
    }

    @Override
    public Key<CalendarService> getExposedKey() {
        return exposedKey;
    }

    @Override
    protected void configure() {
        usernameSpec.bind(String.class).annotatedWith(IcloudCalendarService.Username.class).installedBy(this::installLifecycleComponentModule);
        passwordSpec.bind(String.class).annotatedWith(IcloudCalendarService.Password.class).installedBy(this::installLifecycleComponentModule);
        logSubjectIdSpec.bind(String.class).annotatedWith(IcloudCalendarService.LogSubjectId.class).installedBy(this::installLifecycleComponentModule);
        executorSpec.bind(SchedulingExecutor.class).annotatedWith(IcloudCalendarService.Dependency.class).installedBy(this::installLifecycleComponentModule);

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
        /// The thread name follows the injected subject id, so the default executor is distinguishable per user.
        private BindingSpec<SchedulingExecutor> executorSpec =
                exposedBy(ExecutorProviderModule.builder()
                                                .setThreadName(BindingSpec.<String>annotatedWith(IcloudCalendarService.LogSubjectId.class)
                                                                          .map(new TypeToken<>() {},
                                                                               new TypeToken<>() {},
                                                                               IcloudCalendarModule::threadName))
                                                .withFamily(literally(BASE_EXECUTOR_NAME))
                                                .withAnnotation(forAnnotation(uniqueAnnotation()))
                                                .build());

        public Builder setUsername(BindingSpec<String> usernameSpec) {
            this.usernameSpec = checkNotNull(usernameSpec);
            return this;
        }

        public Builder setPassword(BindingSpec<String> passwordSpec) {
            this.passwordSpec = checkNotNull(passwordSpec);
            return this;
        }

        /// A GDPR-safe subject id (e.g. the internal user id) used to tag this instance's executor thread name, so concurrent per-user instances stay
        /// distinguishable in a shared log and in the executor metrics. Defaults to empty (single-instance use).
        public Builder withLogSubjectId(BindingSpec<String> logSubjectIdSpec) {
            this.logSubjectIdSpec = checkNotNull(logSubjectIdSpec);
            return this;
        }

        /// Runs CalDAV calls on the specified executor. If not specified, uses its own dedicated thread, which suits the blocking CalDAV round trips this
        /// service makes.
        public Builder withExecutor(BindingSpec<SchedulingExecutor> executorSpec) {
            this.executorSpec = checkNotNull(executorSpec);
            return this;
        }

        @Override
        public ExposedKeyModule<CalendarService> build() {
            return new IcloudCalendarModule(usernameSpec, passwordSpec, logSubjectIdSpec, executorSpec, specifiedAnnotation());
        }
    }
}

package net.yudichev.jiotty.common.async.backoff;

import net.yudichev.jiotty.common.async.ExecutorProviderModule;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.misc.SharedUpstreamOutage;
import net.yudichev.jiotty.common.misc.UpstreamHealthHandler;

import java.lang.annotation.Annotation;

import static net.yudichev.jiotty.common.inject.BindingSpec.exposedBy;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.inject.GuiceUtil.uniqueAnnotation;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;

/// The backoff policy shared connectors put between their raw calls and their [UpstreamHealthHandler] reporting.
public final class SharedUpstreamOutageBackOff {
    private SharedUpstreamOutageBackOff() {
    }

    /// The exception-handler spec for [#sharedOutageRetryExecutorModule]: retries exactly the failures [SharedUpstreamOutage#indicatesSharedOutage(Throwable)]
    /// classifies as an outage, backing off per `backOffConfig`; any other failure goes straight through to the caller.
    private static BindingSpec<BackingOffExceptionHandler> sharedOutageRetryHandler(BackOffConfig backOffConfig) {
        return exposedBy(BackingOffExceptionHandlerModule.builder()
                                                         .setRetryableExceptionPredicate(literally(SharedUpstreamOutage::indicatesSharedOutage))
                                                         .withConfig(literally(backOffConfig))
                                                         .build());
    }

    /// A [RetryableOperationExecutorModule] that retries exactly the failures [SharedUpstreamOutage#indicatesSharedOutage(Throwable)] classifies as an outage,
    /// backing off per `backOffConfig` on a dedicated single-threaded executor named `threadName`, its exposed [RetryableOperationExecutor] key annotated with
    /// `annotation` — the whole retry wiring a shared connector's module installs in one call. A transient blip recovers inside the retry loop; only a
    /// sustained outage exhausts the retries and reaches the [UpstreamHealthHandler].
    public static ExposedKeyModule<RetryableOperationExecutor> sharedOutageRetryExecutorModule(String threadName,
                                                                                               Class<? extends Annotation> annotation,
                                                                                               BackOffConfig backOffConfig) {
        return RetryableOperationExecutorModule.builder()
                                               .setBackingOffExceptionHandler(sharedOutageRetryHandler(backOffConfig))
                                               .withExecutor(exposedBy(ExecutorProviderModule.builder()
                                                                                             .setThreadName(literally(threadName))
                                                                                             .withAnnotation(forAnnotation(uniqueAnnotation()))
                                                                                             .build()))
                                               .withAnnotation(forAnnotation(annotation))
                                               .build();
    }
}

package net.yudichev.jiotty.common.async.backoff;

import com.google.inject.Guice;
import com.google.inject.Injector;
import net.yudichev.jiotty.common.async.ExecutorModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.function.Predicate;

import static net.yudichev.jiotty.common.inject.BindingSpec.exposedBy;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;

@ExtendWith(MockitoExtension.class)
class BackingOffExceptionHandlerModuleTest {
    @Mock
    private Predicate<? super Throwable> predicate;

    @Test
    void injector() {
        Injector injector = Guice.createInjector(
                ExecutorModule.builder().build(),
                RetryableOperationExecutorModule.builder()
                                                .setBackingOffExceptionHandler(exposedBy(
                                                        BackingOffExceptionHandlerModule.builder()
                                                                                        .setRetryableExceptionPredicate(literally(predicate))
                                                                                        .build()))
                                                .build());
        injector.getBinding(RetryableOperationExecutor.class);
    }
}
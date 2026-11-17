package net.yudichev.jiotty.user.persistence;

import jakarta.inject.Provider;
import net.yudichev.jiotty.common.async.ListenerBackedTaskExceptionHandlerRegistry;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.async.SingleThreadedSchedulingExecutor;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import net.yudichev.jiotty.persistence.db.DataSourceFactory;
import net.yudichev.jiotty.persistence.domain.PersistenceDomain;
import net.yudichev.jiotty.persistence.domain.PersistenceDomainMigrator;
import net.yudichev.jiotty.persistence.domain.PersistenceDomainServiceImpl;

import java.util.List;

/// A [UserPersistenceImpl] together with the executor and the [PersistenceDomainServiceImpl] on which the store runs. [#start] starts the domain service and
/// then the store, and [#close] stops the store and the domain service, then closes the executor.
final class UserPersistenceImplFixture implements Closeable {
    private final SingleThreadedSchedulingExecutor executor;
    private final PersistenceDomainServiceImpl domainService;
    private final UserPersistenceImpl userPersistence;

    public UserPersistenceImplFixture(DataSourceFactory dataSourceFactory, CurrentDateTimeProvider timeProvider, String executorThreadName) {
        executor = new SingleThreadedSchedulingExecutor(executorThreadName);
        Provider<SchedulingExecutor> executorProvider = () -> executor;
        domainService = new PersistenceDomainServiceImpl(dataSourceFactory, executorProvider, new ListenerBackedTaskExceptionHandlerRegistry());
        userPersistence = new UserPersistenceImpl(dataSourceFactory,
                                                  executorProvider,
                                                  domainService,
                                                  timeProvider,
                                                  1,
                                                  new PersistenceDomain("users").name(),
                                                  List.of(),
                                                  PersistenceDomainMigrator.FAIL_ON_MIGRATION,
                                                  new ListenerBackedTaskExceptionHandlerRegistry());
    }

    public void start() {
        domainService.start();
        userPersistence.start();
    }

    @Override
    public void close() {
        Closeable.closeIfNotNull(userPersistence::stop, domainService::stop, executor);
    }

    public UserPersistence userPersistence() {
        return userPersistence;
    }
}

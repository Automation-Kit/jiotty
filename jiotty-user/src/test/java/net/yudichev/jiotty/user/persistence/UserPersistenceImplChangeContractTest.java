package net.yudichev.jiotty.user.persistence;

import jakarta.inject.Provider;
import net.yudichev.jiotty.common.async.ListenerBackedTaskExceptionHandlerRegistry;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.async.SingleThreadedSchedulingExecutor;
import net.yudichev.jiotty.common.async.TaskFailureReporter;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.persistence.domain.PersistenceDomain;
import net.yudichev.jiotty.persistence.domain.PersistenceDomainMigrator;
import net.yudichev.jiotty.persistence.domain.PersistenceDomainServiceImpl;
import net.yudichev.jiotty.persistence.test.EmbeddedPostgresExtension;
import net.yudichev.jiotty.persistence.test.UsingEmbeddedPostgres;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

/// Runs the shared change-stream contract against the real store, so the contract is stated once and proved on both sides of it.
@UsingEmbeddedPostgres
class UserPersistenceImplChangeContractTest extends BaseUserChangeContractTest {
    private static final TaskFailureReporter taskFailureReporter = new ListenerBackedTaskExceptionHandlerRegistry();

    @RegisterExtension
    private static final EmbeddedPostgresExtension postgres = new EmbeddedPostgresExtension();

    private SingleThreadedSchedulingExecutor executor;
    private PersistenceDomainServiceImpl domainService;
    private UserPersistenceImpl userPersistence;

    @BeforeEach
    void setUp() {
        clock.setTime(START);
        executor = new SingleThreadedSchedulingExecutor("user-change-contract-test");
        Provider<SchedulingExecutor> executorProvider = () -> executor;
        domainService = new PersistenceDomainServiceImpl(postgres.dataSourceFactory(), executorProvider, new ListenerBackedTaskExceptionHandlerRegistry());
        domainService.start();
        userPersistence = new UserPersistenceImpl(postgres.dataSourceFactory(),
                                                  executorProvider,
                                                  domainService,
                                                  clock,
                                                  1,
                                                  new PersistenceDomain("users").name(),
                                                  List.of(),
                                                  PersistenceDomainMigrator.FAIL_ON_MIGRATION,
                                                  taskFailureReporter);
        userPersistence.start();
    }

    @AfterEach
    void tearDown() {
        Closeable.closeIfNotNull(userPersistence == null ? null : userPersistence::stop,
                                 domainService == null ? null : domainService::stop,
                                 executor);
    }

    @Override
    protected UserPersistence userPersistence() {
        return userPersistence;
    }
}

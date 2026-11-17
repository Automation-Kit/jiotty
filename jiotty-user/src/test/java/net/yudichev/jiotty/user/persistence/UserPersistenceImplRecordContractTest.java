package net.yudichev.jiotty.user.persistence;

import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.persistence.test.EmbeddedPostgresExtension;
import net.yudichev.jiotty.persistence.test.UsingEmbeddedPostgres;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;

/// Runs [BaseUserRecordContractTest] against [UserPersistenceImpl]; [FakeUserRecordContractTest] runs the same tests against [FakeUserPersistence].
@UsingEmbeddedPostgres
class UserPersistenceImplRecordContractTest extends BaseUserRecordContractTest {
    @RegisterExtension
    private static final EmbeddedPostgresExtension postgres = new EmbeddedPostgresExtension();

    /// `null` until [#setUp] constructs the fixture.
    private @Nullable UserPersistenceImplFixture fixture;

    @BeforeEach
    void setUp() {
        clock.setTime(START);
        fixture = new UserPersistenceImplFixture(postgres.dataSourceFactory(), clock, "user-record-contract-test");
        fixture.start();
    }

    @AfterEach
    void tearDown() {
        Closeable.closeIfNotNull(fixture);
    }

    @Override
    protected UserPersistence userPersistence() {
        assert fixture != null : "the fixture is started";
        return fixture.userPersistence();
    }
}

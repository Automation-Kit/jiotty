package net.yudichev.jiotty.user.persistence;

import jakarta.inject.Provider;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.async.SingleThreadedSchedulingExecutor;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.persistence.db.CloseableDataSource;
import net.yudichev.jiotty.persistence.db.DataSourceFactory;
import net.yudichev.jiotty.persistence.domain.PersistenceDomain;
import net.yudichev.jiotty.persistence.domain.PersistenceDomainMigrator;
import net.yudichev.jiotty.persistence.domain.PersistenceDomainServiceImpl;
import net.yudichev.jiotty.persistence.test.EmbeddedPostgresExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserPersistenceImplTest {
    private static final String DOMAIN_NAME = "users";
    private static final String EXTRA_INIT_STATEMENT = "CREATE TABLE IF NOT EXISTS %DOMAIN_PREFIX%extra (id integer);";
    private static final String DROP_IDENTITY_TABLE_SQL = "DROP TABLE %DOMAIN_PREFIX%identity";
    private static final String DROP_USER_TABLE_SQL = "DROP TABLE %DOMAIN_PREFIX%user CASCADE";
    private static final String MARK_IDENTITY_DELETED_SQL =
            "UPDATE %DOMAIN_PREFIX%identity SET deleted_at=?, updated_at=? WHERE user_id=? AND provider=?";
    private static final String UPDATE_IDENTITY_PROVIDER_USER_ID_SQL =
            "UPDATE %DOMAIN_PREFIX%identity SET provider_user_id=?, updated_at=? WHERE user_id=? AND provider=?";
    @RegisterExtension
    private final EmbeddedPostgresExtension postgres = new EmbeddedPostgresExtension();
    private DataSource dataSource;
    private DataSourceFactory dataSourceFactory;
    private SingleThreadedSchedulingExecutor executor;
    private Provider<SchedulingExecutor> executorProvider;
    private PersistenceDomainServiceImpl domainService;
    private PersistenceDomain domain;
    private UserPersistenceImpl userPersistence;

    @BeforeEach
    void setUp() {
        dataSource = postgres.dataSource();
        dataSourceFactory = postgres.dataSourceFactory();
        executor = new SingleThreadedSchedulingExecutor("user-persistence-test");
        executorProvider = () -> executor;
        domainService = new PersistenceDomainServiceImpl(dataSourceFactory, executorProvider);
        domain = new PersistenceDomain(DOMAIN_NAME);
        domainService.start();
    }

    @AfterEach
    void tearDown() {
        Closeable.closeIfNotNull(
                () -> {
                    if (userPersistence != null) {
                        userPersistence.stop();
                    }
                },
                () -> {
                    if (domainService != null) {
                        domainService.stop();
                    }
                },
                executor);
    }

    @Test
    void rejectsInvalidSchemaVersion() {
        assertThatThrownBy(() -> new UserPersistenceImpl(dataSourceFactory,
                                                         executorProvider,
                                                         domainService,
                                                         0,
                                                         domain.name(),
                                                         List.of(),
                                                         PersistenceDomainMigrator.FAIL_ON_MIGRATION))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createsUpdatesAndListsIdentities() throws Exception {
        startUserPersistence(dataSourceFactory, List.of(EXTRA_INIT_STATEMENT));
        var identity = new UserPersistence.UserIdentity("firebase", "uid-1");
        var profileInput = new UserPersistence.UserProfileInput("user@example.com", "Alex", ZoneId.of("UTC"));
        var created = userPersistence.getOrCreateByIdentity(identity, profileInput).get(5, TimeUnit.SECONDS);

        assertThat(created.email()).isEqualTo("user@example.com");
        assertThat(created.displayName()).isEqualTo("Alex");
        assertThat(created.timezone()).isEqualTo(ZoneId.of("UTC"));

        var fetched = userPersistence.getOrCreateByIdentity(identity, profileInput).get(5, TimeUnit.SECONDS);
        assertThat(fetched.id()).isEqualTo(created.id());

        var identities = userPersistence.listIdentities(created.id()).get(5, TimeUnit.SECONDS);
        assertThat(identities).hasSize(1);
        assertThat(identities.getFirst().identity()).isEqualTo(identity);

        var googleIdentity = new UserPersistence.UserIdentity("google.com", "google-uid");
        userPersistence.linkIdentity(created.id(), googleIdentity).get(5, TimeUnit.SECONDS);
        userPersistence.linkIdentity(created.id(), googleIdentity).get(5, TimeUnit.SECONDS);

        markIdentityDeleted(dataSource, domain, created.id(), googleIdentity.provider(), Instant.now());
        var deletedIdentities = userPersistence.listIdentities(created.id()).get(5, TimeUnit.SECONDS);
        assertThat(deletedIdentities).hasSize(1);

        userPersistence.linkIdentity(created.id(), googleIdentity).get(5, TimeUnit.SECONDS);
        var linked = userPersistence.listIdentities(created.id()).get(5, TimeUnit.SECONDS);
        assertThat(linked).hasSize(2);

        var duplicateIdentity = new UserPersistence.UserIdentity("apple.com", "apple-uid");
        var duplicateProfile = new UserPersistence.UserProfileInput("user@example.com", "Duplicate", ZoneId.of("UTC"));
        assertThatThrownBy(() -> userPersistence.getOrCreateByIdentity(duplicateIdentity, duplicateProfile).get(5, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(IllegalStateException.class);

        var update = new UserPersistence.UserProfileUpdate("new@example.com", "Alexey", ZoneId.of("Europe/London"));
        var updated = userPersistence.updateProfile(created.id(), update).get(5, TimeUnit.SECONDS);
        assertThat(updated.email()).isEqualTo("new@example.com");
        assertThat(updated.displayName()).isEqualTo("Alexey");
        assertThat(updated.timezone()).isEqualTo(ZoneId.of("Europe/London"));

        userPersistence.softDelete(created.id()).get(5, TimeUnit.SECONDS);
        Optional<UserPersistence.UserProfile> deleted = userPersistence.getById(created.id()).get(5, TimeUnit.SECONDS);
        assertThat(deleted).isEmpty();

        assertThatThrownBy(() -> userPersistence.updateProfile(created.id(), update).get(5, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> userPersistence.softDelete(created.id()).get(5, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void failsOnInsertWhenUserTableMissing() throws Exception {
        startUserPersistence(dataSourceFactory, List.of());
        dropUserTable(dataSource, domain);

        var identity = new UserPersistence.UserIdentity("firebase", "uid-1");
        var profileInput = new UserPersistence.UserProfileInput("user@example.com", "Alex", ZoneId.of("UTC"));
        assertThatThrownBy(() -> userPersistence.getOrCreateByIdentity(identity, profileInput).get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(RuntimeException.class)
                .hasRootCauseInstanceOf(SQLException.class);
    }

    @Test
    void failsWhenConnectionUnavailable() throws Exception {
        var toggleableDataSource = new ToggleableDataSource(dataSource);
        DataSourceFactory failingFactory = () -> toggleableDataSource;
        startUserPersistence(failingFactory, List.of());
        toggleableDataSource.setFailConnections(true);

        var identity = new UserPersistence.UserIdentity("firebase", "uid-1");
        var profileInput = new UserPersistence.UserProfileInput("user@example.com", "Alex", ZoneId.of("UTC"));
        assertThatThrownBy(() -> userPersistence.getOrCreateByIdentity(identity, profileInput).get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(RuntimeException.class)
                .hasRootCauseInstanceOf(SQLException.class);
    }

    @Test
    void failsOnCorruptIdentityProviderUserId() throws Exception {
        startUserPersistence(dataSourceFactory, List.of());
        var identity = new UserPersistence.UserIdentity("firebase", "uid-1");
        var profileInput = new UserPersistence.UserProfileInput("user@example.com", "Alex", ZoneId.of("UTC"));
        var created = userPersistence.getOrCreateByIdentity(identity, profileInput).get(5, TimeUnit.SECONDS);

        setIdentityProviderUserId(dataSource, domain, created.id(), identity.provider(), "");

        assertThatThrownBy(() -> userPersistence.linkIdentity(created.id(), identity).get(5, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void failsOnLinkIdentityWhenIdentityTableMissing() throws Exception {
        startUserPersistence(dataSourceFactory, List.of());
        var identity = new UserPersistence.UserIdentity("firebase", "uid-1");
        var profileInput = new UserPersistence.UserProfileInput("user@example.com", "Alex", ZoneId.of("UTC"));
        var created = userPersistence.getOrCreateByIdentity(identity, profileInput).get(5, TimeUnit.SECONDS);

        dropIdentityTable(dataSource, domain);

        var otherIdentity = new UserPersistence.UserIdentity("google.com", "uid-2");
        assertThatThrownBy(() -> userPersistence.linkIdentity(created.id(), otherIdentity).get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(RuntimeException.class)
                .hasRootCauseInstanceOf(SQLException.class);
    }

    @Test
    void listsAllProfilesExcludingDeleted() throws Exception {
        startUserPersistence(dataSourceFactory, List.of());
        var identity1 = new UserPersistence.UserIdentity("firebase", "uid-1");
        var profile1 = new UserPersistence.UserProfileInput("user1@example.com", "User One", ZoneId.of("UTC"));
        var user1 = userPersistence.getOrCreateByIdentity(identity1, profile1).get(5, TimeUnit.SECONDS);

        var identity2 = new UserPersistence.UserIdentity("google.com", "uid-2");
        var profile2 = new UserPersistence.UserProfileInput("user2@example.com", "User Two", ZoneId.of("UTC"));
        var user2 = userPersistence.getOrCreateByIdentity(identity2, profile2).get(5, TimeUnit.SECONDS);

        var profiles = userPersistence.listAllProfiles().get(5, TimeUnit.SECONDS);
        assertThat(profiles).hasSize(2);
        assertThat(profiles).extracting(UserPersistence.UserProfile::id).contains(user1.id(), user2.id());

        userPersistence.softDelete(user1.id()).get(5, TimeUnit.SECONDS);
        var remaining = userPersistence.listAllProfiles().get(5, TimeUnit.SECONDS);
        assertThat(remaining).hasSize(1);
        assertThat(remaining.getFirst().id()).isEqualTo(user2.id());
    }

    @Test
    void rejectsLinkingIdentityToAnotherUser() throws Exception {
        startUserPersistence(dataSourceFactory, List.of());
        var identity = new UserPersistence.UserIdentity("firebase", "uid-1");
        var profileInput = new UserPersistence.UserProfileInput("user@example.com", "Alex", ZoneId.of("UTC"));
        userPersistence.getOrCreateByIdentity(identity, profileInput).get(5, TimeUnit.SECONDS);

        var otherIdentity = new UserPersistence.UserIdentity("google.com", "uid-2");
        var otherProfile = new UserPersistence.UserProfileInput("other@example.com", "Morgan", ZoneId.of("UTC"));
        var user2 = userPersistence.getOrCreateByIdentity(otherIdentity, otherProfile).get(5, TimeUnit.SECONDS);

        assertThatThrownBy(() -> userPersistence.linkIdentity(user2.id(), identity).get(5, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(IllegalStateException.class);
    }

    private void startUserPersistence(DataSourceFactory factory, List<String> initStatements) {
        userPersistence = new UserPersistenceImpl(factory,
                                                  executorProvider,
                                                  domainService,
                                                  1,
                                                  domain.name(),
                                                  initStatements,
                                                  PersistenceDomainMigrator.FAIL_ON_MIGRATION);
        userPersistence.start();
    }

    private static void markIdentityDeleted(DataSource dataSource,
                                            PersistenceDomain domain,
                                            UUID userId,
                                            String provider,
                                            Instant timestamp) throws SQLException {
        var sql = expandSql(MARK_IDENTITY_DELETED_SQL, domain);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.from(timestamp));
            statement.setTimestamp(2, Timestamp.from(timestamp));
            statement.setObject(3, userId);
            statement.setString(4, provider);
            statement.executeUpdate();
        }
    }

    private static void setIdentityProviderUserId(DataSource dataSource,
                                                  PersistenceDomain domain,
                                                  UUID userId,
                                                  String provider,
                                                  String providerUserId) throws SQLException {
        var sql = expandSql(UPDATE_IDENTITY_PROVIDER_USER_ID_SQL, domain);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, providerUserId);
            statement.setTimestamp(2, Timestamp.from(Instant.now()));
            statement.setObject(3, userId);
            statement.setString(4, provider);
            statement.executeUpdate();
        }
    }

    private static void dropIdentityTable(DataSource dataSource, PersistenceDomain domain) throws SQLException {
        var sql = expandSql(DROP_IDENTITY_TABLE_SQL, domain);
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void dropUserTable(DataSource dataSource, PersistenceDomain domain) throws SQLException {
        var sql = expandSql(DROP_USER_TABLE_SQL, domain);
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String expandSql(String sql, PersistenceDomain domain) {
        return sql.replace("%DOMAIN_PREFIX%", domain.prefix());
    }

    private static final class ToggleableDataSource implements CloseableDataSource {
        private final DataSource delegate;
        private final AtomicBoolean failConnections = new AtomicBoolean();

        private ToggleableDataSource(DataSource delegate) {
            this.delegate = checkNotNull(delegate, "delegate");
        }

        private void setFailConnections(boolean failConnections) {
            this.failConnections.set(failConnections);
        }

        @Override
        public Connection getConnection() throws SQLException {
            if (failConnections.get()) {
                throw new SQLException("Simulated connection failure");
            }
            return delegate.getConnection();
        }

        @Override
        public void close() {
        }
    }

}

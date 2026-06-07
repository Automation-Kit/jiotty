package net.yudichev.jiotty.user.persistence;

import jakarta.inject.Provider;
import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.async.SingleThreadedSchedulingExecutor;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import net.yudichev.jiotty.common.time.TimeProvider;
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
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Optional.of;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserPersistenceImplTest {
    private static final String DOMAIN_NAME = "users";
    private static final String EXTRA_INIT_STATEMENT = "CREATE TABLE IF NOT EXISTS %DOMAIN_PREFIX%extra (id integer);";
    private static final String DROP_IDENTITY_TABLE_SQL = "DROP TABLE %DOMAIN_PREFIX%identity";
    private static final String DROP_USER_TABLE_SQL = "DROP TABLE %DOMAIN_PREFIX%user CASCADE";
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final ZoneId EUROPE_LONDON = ZoneId.of("Europe/London");

    @RegisterExtension
    private static final EmbeddedPostgresExtension postgres = new EmbeddedPostgresExtension();
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
        Closeable.closeIfNotNull(userPersistence == null ? null : userPersistence::stop, domainService == null ? null : domainService::stop, executor);
    }

    @Test
    void rejectsInvalidSchemaVersion() {
        assertThatThrownBy(() -> new UserPersistenceImpl(dataSourceFactory,
                                                         executorProvider,
                                                         domainService,
                                                         new TimeProvider(),
                                                         0,
                                                         domain.name(),
                                                         List.of(),
                                                         PersistenceDomainMigrator.FAIL_ON_MIGRATION))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createsUpdatesAndListsIdentities() throws Exception {
        startUserPersistence(dataSourceFactory, List.of(EXTRA_INIT_STATEMENT));
        var identity = new UserIdentity("firebase", "uid-1");
        var profileInput = createProfileInput("user@example.com", "Alex", UTC);
        var created = userPersistence.getOrCreateByIdentity(identity, profileInput).get(5, SECONDS);

        assertThat(created.id()).startsWith("u");
        assertThat(created.email()).contains("user@example.com");
        assertThat(created.displayName()).contains("Alex");
        assertThat(created.timezone()).isEqualTo(UTC);

        var fetched = userPersistence.getOrCreateByIdentity(identity, profileInput).get(5, SECONDS);
        assertThat(fetched.id()).isEqualTo(created.id());

        var identities = userPersistence.listIdentities(created.id()).get(5, SECONDS);
        assertThat(identities).hasSize(1);
        assertThat(identities.getFirst().identity()).isEqualTo(identity);

        var googleIdentity = new UserIdentity("google.com", "google-uid");
        userPersistence.updateAllIdentities(created.id(), List.of(identity, googleIdentity)).get(5, SECONDS);
        userPersistence.updateAllIdentities(created.id(), List.of(identity, googleIdentity)).get(5, SECONDS);

        userPersistence.updateAllIdentities(created.id(), List.of(identity)).get(5, SECONDS);
        var deletedIdentities = userPersistence.listIdentities(created.id()).get(5, SECONDS);
        assertThat(deletedIdentities).hasSize(1);
        assertThat(userPersistence.getByIdentity(googleIdentity).get(5, SECONDS)).isEmpty();

        userPersistence.updateAllIdentities(created.id(), List.of(identity, googleIdentity)).get(5, SECONDS);
        var linked = userPersistence.listIdentities(created.id()).get(5, SECONDS);
        assertThat(linked).hasSize(2);
        assertThat(userPersistence.getByIdentity(googleIdentity).get(5, SECONDS)).contains(created);

        var duplicateIdentity = new UserIdentity("apple.com", "apple-uid");
        var duplicateProfile = createProfileInput("user@example.com", "Duplicate", UTC);
        assertThatThrownBy(() -> userPersistence.getOrCreateByIdentity(duplicateIdentity, duplicateProfile).get(5, SECONDS))
                .hasRootCauseInstanceOf(IllegalStateException.class);

        var update = createProfileInput("new@example.com", "Alexey", EUROPE_LONDON);
        var updated = userPersistence.updateProfile(created.id(), update).get(5, SECONDS);
        assertThat(updated.email()).contains("new@example.com");
        assertThat(updated.displayName()).contains("Alexey");
        assertThat(updated.timezone()).isEqualTo(EUROPE_LONDON);

        userPersistence.softDelete(created.id()).get(5, SECONDS);
        Optional<UserProfile> deleted = userPersistence.getById(created.id()).get(5, SECONDS);
        assertThat(deleted).isEmpty();

        assertThatThrownBy(() -> userPersistence.updateProfile(created.id(), update).get(5, SECONDS))
                .hasRootCauseInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> userPersistence.softDelete(created.id()).get(5, SECONDS))
                .hasRootCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void failsOnInsertWhenUserTableMissing() throws Exception {
        startUserPersistence(dataSourceFactory, List.of());
        dropUserTable(dataSource, domain);

        var identity = new UserIdentity("firebase", "uid-1");
        var profileInput = createProfileInput("user@example.com", "Alex", UTC);
        assertThatThrownBy(() -> userPersistence.getOrCreateByIdentity(identity, profileInput).get(5, SECONDS))
                .hasCauseInstanceOf(RuntimeException.class)
                .hasRootCauseInstanceOf(SQLException.class);
    }

    @Test
    void failsWhenConnectionUnavailable() {
        var toggleableDataSource = new ToggleableDataSource(dataSource);
        DataSourceFactory failingFactory = () -> toggleableDataSource;
        startUserPersistence(failingFactory, List.of());
        toggleableDataSource.setFailConnections(true);

        var identity = new UserIdentity("firebase", "uid-1");
        var profileInput = createProfileInput("user@example.com", "Alex", UTC);
        assertThatThrownBy(() -> userPersistence.getOrCreateByIdentity(identity, profileInput).get(5, SECONDS))
                .hasCauseInstanceOf(RuntimeException.class)
                .hasRootCauseInstanceOf(SQLException.class);
    }

    @Test
    void updatesProviderUserIdForExistingProvider() throws Exception {
        startUserPersistence(dataSourceFactory, List.of());
        var identity = new UserIdentity("firebase", "uid-1");
        var profileInput = createProfileInput("user@example.com", "Alex", UTC);
        var created = userPersistence.getOrCreateByIdentity(identity, profileInput).get(5, SECONDS);
        var oldGoogleIdentity = new UserIdentity("google.com", "google-uid-1");
        var newGoogleIdentity = new UserIdentity("google.com", "google-uid-2");

        userPersistence.updateAllIdentities(created.id(), List.of(identity, oldGoogleIdentity)).get(5, SECONDS);
        userPersistence.updateAllIdentities(created.id(), List.of(identity, newGoogleIdentity)).get(5, SECONDS);

        var identities = userPersistence.listIdentities(created.id()).get(5, SECONDS);
        assertThat(identities).hasSize(2);
        assertThat(identities).extracting(UserIdentityRecord::identity).contains(identity, newGoogleIdentity);
        assertThat(identities).extracting(UserIdentityRecord::identity).doesNotContain(oldGoogleIdentity);
        assertThat(userPersistence.getByIdentity(oldGoogleIdentity).get(5, SECONDS)).isEmpty();
        assertThat(userPersistence.getByIdentity(newGoogleIdentity).get(5, SECONDS)).contains(created);
    }

    @Test
    void failsOnUpdateAllIdentitiesWhenIdentityTableMissing() throws Exception {
        startUserPersistence(dataSourceFactory, List.of());
        var identity = new UserIdentity("firebase", "uid-1");
        var profileInput = createProfileInput("user@example.com", "Alex", UTC);
        var created = userPersistence.getOrCreateByIdentity(identity, profileInput).get(5, SECONDS);

        dropIdentityTable(dataSource, domain);

        var otherIdentity = new UserIdentity("google.com", "uid-2");
        assertThatThrownBy(() -> userPersistence.updateAllIdentities(created.id(), List.of(identity, otherIdentity)).get(5, SECONDS))
                .hasCauseInstanceOf(RuntimeException.class)
                .hasRootCauseInstanceOf(SQLException.class);
    }

    @Test
    void listsAllProfilesExcludingDeleted() throws Exception {
        startUserPersistence(dataSourceFactory, List.of());
        var identity1 = new UserIdentity("firebase", "uid-1");
        var profile1 = createProfileInput("user1@example.com", "User One", UTC);
        var user1 = userPersistence.getOrCreateByIdentity(identity1, profile1).get(5, SECONDS);

        var identity2 = new UserIdentity("google.com", "uid-2");
        var profile2 = createProfileInput("user2@example.com", "User Two", UTC);
        var user2 = userPersistence.getOrCreateByIdentity(identity2, profile2).get(5, SECONDS);

        var profiles = userPersistence.listAllProfiles().get(5, SECONDS);
        assertThat(profiles).hasSize(2);
        assertThat(profiles).extracting(UserProfile::id).contains(user1.id(), user2.id());

        userPersistence.softDelete(user1.id()).get(5, SECONDS);
        var remaining = userPersistence.listAllProfiles().get(5, SECONDS);
        assertThat(remaining).hasSize(1);
        assertThat(remaining.getFirst().id()).isEqualTo(user2.id());
    }

    @Test
    void getsByIdentityExcludingDeletedUsers() throws Exception {
        startUserPersistence(dataSourceFactory, List.of());
        var identity = new UserIdentity("firebase", "uid-1");
        var profileInput = createProfileInput("user@example.com", "Alex", UTC);
        var created = userPersistence.getOrCreateByIdentity(identity, profileInput).get(5, SECONDS);

        assertThat(userPersistence.getByIdentity(identity).get(5, SECONDS)).contains(created);

        userPersistence.softDelete(created.id()).get(5, SECONDS);

        assertThat(userPersistence.getByIdentity(identity).get(5, SECONDS)).isEmpty();
    }

    @Test
    void hardDeleteRemovesUserAndIdentitiesPermanently() throws Exception {
        startUserPersistence(dataSourceFactory, List.of());
        var identity = new UserIdentity("firebase", "uid-1");
        var created = userPersistence.getOrCreateByIdentity(identity, createProfileInput("user@example.com", "Alex", UTC)).get(5, SECONDS);

        userPersistence.softDelete(created.id()).get(5, SECONDS);
        userPersistence.hardDelete(created.id()).get(5, SECONDS);

        assertThat(userPersistence.listAllProfiles().get(5, SECONDS)).isEmpty();

        // the identity row is physically gone (not merely soft-deleted), so re-registering the same identity yields a brand-new user
        var recreated = userPersistence.getOrCreateByIdentity(identity, createProfileInput("user@example.com", "Alex", UTC)).get(5, SECONDS);
        assertThat(recreated.id()).isNotEqualTo(created.id());
        assertThat(userPersistence.listIdentities(recreated.id()).get(5, SECONDS)).hasSize(1);
    }

    @Test
    void hardDeleteIsIdempotentForUnknownUser() throws Exception {
        startUserPersistence(dataSourceFactory, List.of());
        // completes normally even though no such user exists
        userPersistence.hardDelete("u-does-not-exist").get(5, SECONDS);
    }

    @Test
    void restoreRevivesOnlyIdentitiesSoftDeletedWithTheAccount() throws Exception {
        var clock = new ProgrammableClock();
        clock.setTime(Instant.parse("2026-01-01T00:00:00Z"));
        startUserPersistence(dataSourceFactory, List.of(), clock);

        var firebase = new UserIdentity("firebase", "uid-1");
        var google = new UserIdentity("google.com", "g-1");
        var created = userPersistence.getOrCreateByIdentity(firebase, createProfileInput("user@example.com", "Alex", UTC)).get(5, SECONDS);
        userPersistence.updateAllIdentities(created.id(), List.of(firebase, google)).get(5, SECONDS);

        // google is unlinked earlier, at a DIFFERENT timestamp than the later account deletion
        clock.setTime(Instant.parse("2026-02-01T00:00:00Z"));
        userPersistence.updateAllIdentities(created.id(), List.of(firebase)).get(5, SECONDS);

        // the account is soft-deleted later: the user and the still-active firebase identity get this later timestamp
        clock.setTime(Instant.parse("2026-03-01T00:00:00Z"));
        userPersistence.softDelete(created.id()).get(5, SECONDS);
        assertThat(userPersistence.getByIdentity(firebase).get(5, SECONDS)).isEmpty();

        userPersistence.restore(created.id()).get(5, SECONDS);

        // firebase (soft-deleted with the account) is revived; google (removed earlier, different timestamp) stays removed
        assertThat(userPersistence.getById(created.id()).get(5, SECONDS)).isPresent();
        assertThat(userPersistence.getByIdentity(firebase).get(5, SECONDS)).isPresent();
        assertThat(userPersistence.getByIdentity(google).get(5, SECONDS)).isEmpty();
        assertThat(userPersistence.listIdentities(created.id()).get(5, SECONDS))
                .extracting(record -> record.identity().provider())
                .containsExactly("firebase");
    }

    @Test
    void restoreIsNoOpForActiveUser() throws Exception {
        startUserPersistence(dataSourceFactory, List.of());
        var identity = new UserIdentity("firebase", "uid-1");
        var created = userPersistence.getOrCreateByIdentity(identity, createProfileInput("user@example.com", "Alex", UTC)).get(5, SECONDS);

        userPersistence.restore(created.id()).get(5, SECONDS); // not soft-deleted: no-op

        assertThat(userPersistence.getById(created.id()).get(5, SECONDS)).isPresent();
    }

    @Test
    void rejectsUpdatingIdentityToAnotherUser() throws Exception {
        startUserPersistence(dataSourceFactory, List.of());
        var identity = new UserIdentity("firebase", "uid-1");
        var profileInput = createProfileInput("user@example.com", "Alex", UTC);
        userPersistence.getOrCreateByIdentity(identity, profileInput).get(5, SECONDS);

        var otherIdentity = new UserIdentity("google.com", "uid-2");
        var otherProfile = createProfileInput("other@example.com", "Morgan", UTC);
        var user2 = userPersistence.getOrCreateByIdentity(otherIdentity, otherProfile).get(5, SECONDS);

        assertThatThrownBy(() -> userPersistence.updateAllIdentities(user2.id(), List.of(otherIdentity, identity)).get(5, SECONDS))
                .hasRootCauseInstanceOf(IllegalStateException.class);
    }

    private void startUserPersistence(DataSourceFactory factory, List<String> initStatements) {
        startUserPersistence(factory, initStatements, new TimeProvider());
    }

    private void startUserPersistence(DataSourceFactory factory, List<String> initStatements, CurrentDateTimeProvider timeProvider) {
        userPersistence = new UserPersistenceImpl(factory,
                                                  executorProvider,
                                                  domainService,
                                                  timeProvider,
                                                  1,
                                                  domain.name(),
                                                  initStatements,
                                                  PersistenceDomainMigrator.FAIL_ON_MIGRATION);
        userPersistence.start();
    }

    private static UserProfileInput createProfileInput(String email, String displayName, ZoneId timezone) {
        return new UserProfileInput(of(email), of(displayName), timezone);
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

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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.util.concurrent.Uninterruptibles.awaitUninterruptibly;
import static java.util.Optional.of;
import static java.util.concurrent.TimeUnit.SECONDS;
import static net.yudichev.jiotty.user.persistence.UserPersistence.UserCreationResult;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.list;

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
    /// The second instance's executor in the concurrent-creation test. A separate thread is the whole point: one instance's single-threaded executor serialises
    /// its own tasks, so a race can only be staged between two instances.
    private SingleThreadedSchedulingExecutor racingExecutor;
    private Provider<SchedulingExecutor> executorProvider;
    private PersistenceDomainServiceImpl domainService;
    private PersistenceDomain domain;
    private UserPersistenceImpl userPersistence;

    @BeforeEach
    void setUp() {
        dataSource = postgres.dataSource();
        dataSourceFactory = postgres.dataSourceFactory();
        executor = new SingleThreadedSchedulingExecutor("user-persistence-test");
        racingExecutor = new SingleThreadedSchedulingExecutor("user-persistence-test-racer");
        executorProvider = () -> executor;
        domainService = new PersistenceDomainServiceImpl(dataSourceFactory, executorProvider);
        domain = new PersistenceDomain(DOMAIN_NAME);
        domainService.start();
    }

    @AfterEach
    void tearDown() {
        Closeable.closeIfNotNull(userPersistence == null ? null : userPersistence::stop, domainService == null ? null : domainService::stop,
                                 executor, racingExecutor);
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
        var created = createUser(identity, profileInput);

        assertThat(created.id()).startsWith("u");
        assertThat(created.email()).contains("user@example.com");
        assertThat(created.displayName()).contains("Alex");
        assertThat(created.timezone()).isEqualTo(UTC);

        var fetched = createUser(identity, profileInput);
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

        // A brand-new identity carrying an email that already backs another user cannot be created: the unique `email` column rejects it. The store reports
        // that as a value the caller branches on, NOT a failure — a failure here would surface as a 503 the user could never get past.
        var duplicateIdentity = new UserIdentity("apple.com", "apple-uid");
        var duplicateProfile = createProfileInput("user@example.com", "Duplicate", UTC);
        assertThat(userPersistence.getOrCreateByIdentity(duplicateIdentity, duplicateProfile).get(5, SECONDS))
                .isSameAs(UserCreationResult.EmailAlreadyInUse.INSTANCE);
        assertThat(userPersistence.getByIdentity(duplicateIdentity).get(5, SECONDS)).isEmpty();

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
        // a second soft-delete is an idempotent no-op (not an error)
        userPersistence.softDelete(created.id()).get(5, SECONDS);
        assertThat(userPersistence.getById(created.id()).get(5, SECONDS)).isEmpty();
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
    void refusesCreationWhenTheEmailBelongsToASoftDeletedUser() throws Exception {
        // A soft-deleted user keeps its email until it is hard-deleted, so the address is still taken. The recovery path
        // must decide from the identity, not from a select that hides deleted rows.
        startUserPersistence(dataSourceFactory, List.of());
        var created = createUser(new UserIdentity("firebase", "uid-1"), createProfileInput("user@example.com", "Alex", UTC));
        userPersistence.softDelete(created.id()).get(5, SECONDS);

        var creation = userPersistence.getOrCreateByIdentity(new UserIdentity("firebase", "uid-2"),
                                                             createProfileInput("user@example.com", "Other", UTC)).get(5, SECONDS);

        assertThat(creation).isSameAs(UserCreationResult.EmailAlreadyInUse.INSTANCE);
    }

    @Test
    void concurrentCreationOfOneIdentity_bothCallersResolveToTheSameUser() throws Exception {
        // Two instances, so two connections race the way two server processes would — one instance's single-threaded
        // executor could never interleave with itself. The racing instance blocks in the create transaction after its
        // identity lookup came back empty; the other then claims that identity and commits, which is the only way to
        // reach the unique-violation recovery's Active arm.
        startUserPersistence(dataSourceFactory, List.of());
        var identity = new UserIdentity("firebase", "uid-1");
        var atRaceWindow = new CountDownLatch(1);
        var competitorCommitted = new CountDownLatch(1);
        var racer = new UserPersistenceImpl(dataSourceFactory, () -> racingExecutor, domainService, new TimeProvider(), 1,
                                            domain.name(), List.of(), PersistenceDomainMigrator.FAIL_ON_MIGRATION) {
            @Override
            boolean onBeforeInsertingNewUser() {
                atRaceWindow.countDown();
                return awaitUninterruptibly(competitorCommitted, 10, SECONDS);
            }
        };
        racer.start();

        // Distinct emails, so the ONLY constraint the racer can violate is the identity's — an email collision would take
        // a different arm and prove nothing about this one.
        var racerCreation = racer.getOrCreateByIdentity(identity, createProfileInput("racer@example.com", "Racer", UTC));
        assertThat(atRaceWindow.await(10, SECONDS))
                .as("racer reached the create window — if this times out, assertions are off and the `assert` seam never ran (needs -ea)")
                .isTrue();
        var winner = createUser(identity, createProfileInput("winner@example.com", "Winner", UTC));
        competitorCommitted.countDown();

        // The racer loses the insert and adopts the winner's user rather than failing or creating a second one.
        assertThat(racerCreation).succeedsWithin(Duration.ofSeconds(10)).isEqualTo(new UserCreationResult.Resolved(winner));
        // Its own half-written user row was rolled back, so the race leaves no orphan behind.
        assertThat(userPersistence.listAllProfiles()).succeedsWithin(Duration.ofSeconds(5)).asInstanceOf(list(UserProfile.class)).containsExactly(winner);
        Closeable.closeIfNotNull(racer::stop);
    }

    @Test
    void refusesCreationForAnIdentityHeldByASoftDeletedUser() throws Exception {
        // Not an email conflict: the identity itself is unavailable. Reporting EmailAlreadyInUse here would tell the caller
        // to try a different sign-in method when the real answer is that this account is pending deletion.
        startUserPersistence(dataSourceFactory, List.of());
        var identity = new UserIdentity("firebase", "uid-1");
        var created = createUser(identity, createProfileInput("user@example.com", "Alex", UTC));
        userPersistence.softDelete(created.id()).get(5, SECONDS);

        assertThatThrownBy(() -> userPersistence.getOrCreateByIdentity(identity, createProfileInput("fresh@example.com", "Alex", UTC)).get(5, SECONDS))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .rootCause().hasMessageContaining("soft-deleted");
    }

    @Test
    void existsIgnoringDeletionSeesSoftDeletedButNotHardDeletedUsers() throws Exception {
        startUserPersistence(dataSourceFactory, List.of());
        var created = createUser(new UserIdentity("firebase", "uid-1"),
                                 createProfileInput("user@example.com", "Alex", UTC));

        assertThat(userPersistence.existsIgnoringDeletion(created.id()).get(5, SECONDS)).isTrue();
        assertThat(userPersistence.existsIgnoringDeletion("u-does-not-exist").get(5, SECONDS)).isFalse();

        userPersistence.softDelete(created.id()).get(5, SECONDS);
        assertThat(userPersistence.existsIgnoringDeletion(created.id()).get(5, SECONDS)).isTrue();

        userPersistence.hardDelete(created.id()).get(5, SECONDS);
        assertThat(userPersistence.existsIgnoringDeletion(created.id()).get(5, SECONDS)).isFalse();
    }

    @Test
    void failsListingProfilesIgnoringDeletionWhenUserTableMissing() throws Exception {
        startUserPersistence(dataSourceFactory, List.of());
        dropUserTable(dataSource, domain);

        assertThatThrownBy(() -> userPersistence.listAllProfilesIgnoringDeletion().get(5, SECONDS))
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
        var created = createUser(identity, profileInput);
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
        var created = createUser(identity, profileInput);

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
        var user1 = createUser(identity1, profile1);

        var identity2 = new UserIdentity("google.com", "uid-2");
        var profile2 = createProfileInput("user2@example.com", "User Two", UTC);
        var user2 = createUser(identity2, profile2);

        var profiles = userPersistence.listAllProfiles().get(5, SECONDS);
        assertThat(profiles).hasSize(2);
        assertThat(profiles).extracting(UserProfile::id).contains(user1.id(), user2.id());

        userPersistence.softDelete(user1.id()).get(5, SECONDS);
        var remaining = userPersistence.listAllProfiles().get(5, SECONDS);
        assertThat(remaining).hasSize(1);
        assertThat(remaining.getFirst().id()).isEqualTo(user2.id());
    }

    @Test
    void listsAllProfilesIgnoringDeletionWithDeletedAt() throws Exception {
        var clock = new ProgrammableClock();
        var softDeleteTime = Instant.parse("2026-01-02T03:04:05Z");
        clock.setTime(softDeleteTime);
        startUserPersistence(dataSourceFactory, List.of(), clock);
        var active = createUser(new UserIdentity("firebase", "uid-1"),
                                createProfileInput("user1@example.com", "User One", UTC));
        var deleted = createUser(new UserIdentity("google.com", "uid-2"),
                                 createProfileInput("user2@example.com", "User Two", UTC));

        userPersistence.softDelete(deleted.id()).get(5, SECONDS);

        // the soft-deleted user is hidden from listAllProfiles but reported by the ignoring-deletion list with its deletion time
        assertThat(userPersistence.listAllProfiles().get(5, SECONDS)).extracting(UserProfile::id).containsExactly(active.id());
        var all = userPersistence.listAllProfilesIgnoringDeletion().get(5, SECONDS);
        assertThat(all).extracting(withDeletion -> withDeletion.profile().id()).containsExactlyInAnyOrder(active.id(), deleted.id());
        assertThat(all).filteredOn(withDeletion -> withDeletion.profile().id().equals(active.id()))
                       .singleElement()
                       .satisfies(withDeletion -> assertThat(withDeletion.deletedAt()).isEmpty());
        assertThat(all).filteredOn(withDeletion -> withDeletion.profile().id().equals(deleted.id()))
                       .singleElement()
                       .satisfies(withDeletion -> assertThat(withDeletion.deletedAt()).contains(softDeleteTime));

        // once hard-deleted the row is physically gone even from the ignoring-deletion list
        userPersistence.hardDelete(deleted.id()).get(5, SECONDS);
        assertThat(userPersistence.listAllProfilesIgnoringDeletion().get(5, SECONDS))
                .extracting(withDeletion -> withDeletion.profile().id())
                .containsExactly(active.id());
    }

    @Test
    void getsByIdentityExcludingDeletedUsers() throws Exception {
        startUserPersistence(dataSourceFactory, List.of());
        var identity = new UserIdentity("firebase", "uid-1");
        var profileInput = createProfileInput("user@example.com", "Alex", UTC);
        var created = createUser(identity, profileInput);

        assertThat(userPersistence.getByIdentity(identity).get(5, SECONDS)).contains(created);

        userPersistence.softDelete(created.id()).get(5, SECONDS);

        assertThat(userPersistence.getByIdentity(identity).get(5, SECONDS)).isEmpty();
    }

    @Test
    void resolveByIdentityDifferentiatesActiveSoftDeletedAndAbsent() throws Exception {
        var clock = new ProgrammableClock();
        var softDeleteTime = Instant.parse("2026-01-02T03:04:05Z");
        clock.setTime(softDeleteTime);
        startUserPersistence(dataSourceFactory, List.of(), clock);
        var identity = new UserIdentity("firebase", "uid-1");
        var created = createUser(identity, createProfileInput("user@example.com", "Alex", UTC));

        // an active user resolves to Active
        assertThat(userPersistence.resolveByIdentity(identity).get(5, SECONDS))
                .isInstanceOfSatisfying(UserPersistence.IdentityResolution.Active.class,
                                        active -> assertThat(active.profile().id()).isEqualTo(created.id()));

        userPersistence.softDelete(created.id()).get(5, SECONDS);

        // once soft-deleted: getByIdentity no longer sees it, but resolveByIdentity reports SoftDeleted with the profile
        assertThat(userPersistence.getByIdentity(identity).get(5, SECONDS)).isEmpty();
        assertThat(userPersistence.resolveByIdentity(identity).get(5, SECONDS))
                .isInstanceOfSatisfying(UserPersistence.IdentityResolution.SoftDeleted.class,
                                        softDeleted -> assertThat(softDeleted.profile().id()).isEqualTo(created.id()));

        // after hard-delete the identity row is gone, so resolution is Absent
        userPersistence.hardDelete(created.id()).get(5, SECONDS);
        assertThat(userPersistence.resolveByIdentity(identity).get(5, SECONDS))
                .isInstanceOf(UserPersistence.IdentityResolution.Absent.class);
    }

    @Test
    void resolveByIdentityIsAbsentForUnknownIdentity() throws Exception {
        startUserPersistence(dataSourceFactory, List.of());
        assertThat(userPersistence.resolveByIdentity(new UserIdentity("firebase", "no-such-uid")).get(5, SECONDS))
                .isInstanceOf(UserPersistence.IdentityResolution.Absent.class);
    }

    @Test
    void hardDeleteRemovesUserAndIdentitiesPermanently() throws Exception {
        startUserPersistence(dataSourceFactory, List.of());
        var identity = new UserIdentity("firebase", "uid-1");
        var created = createUser(identity, createProfileInput("user@example.com", "Alex", UTC));

        userPersistence.softDelete(created.id()).get(5, SECONDS);
        userPersistence.hardDelete(created.id()).get(5, SECONDS);

        assertThat(userPersistence.listAllProfiles().get(5, SECONDS)).isEmpty();

        // the identity row is physically gone (not merely soft-deleted), so re-registering the same identity yields a brand-new user
        var recreated = createUser(identity, createProfileInput("user@example.com", "Alex", UTC));
        assertThat(recreated.id()).isNotEqualTo(created.id());
        assertThat(userPersistence.listIdentities(recreated.id()).get(5, SECONDS)).hasSize(1);
    }

    @Test
    void listIdentitiesIgnoringDeletionReturnsIdentitiesOfSoftDeletedUser() throws Exception {
        startUserPersistence(dataSourceFactory, List.of());
        var identity = new UserIdentity("firebase", "uid-1");
        var created = createUser(identity, createProfileInput("user@example.com", "Alex", UTC));

        userPersistence.softDelete(created.id()).get(5, SECONDS);

        // the soft-delete-filtering list hides the deleted user's identities, but the ignoring-deletion list still returns the surviving rows
        assertThat(userPersistence.listIdentities(created.id()).get(5, SECONDS)).isEmpty();
        assertThat(userPersistence.listIdentitiesIgnoringDeletion(created.id()).get(5, SECONDS))
                .extracting(UserIdentityRecord::identity)
                .containsExactly(identity);

        // once hard-deleted the rows are physically gone, so even the ignoring-deletion list is empty
        userPersistence.hardDelete(created.id()).get(5, SECONDS);
        assertThat(userPersistence.listIdentitiesIgnoringDeletion(created.id()).get(5, SECONDS)).isEmpty();
    }

    @Test
    void hardDeleteIsIdempotentForUnknownUser() throws Exception {
        startUserPersistence(dataSourceFactory, List.of());
        // completes normally even though no such user exists
        userPersistence.hardDelete("u-does-not-exist").get(5, SECONDS);
    }

    @Test
    void softDeleteIsIdempotent() throws Exception {
        startUserPersistence(dataSourceFactory, List.of());
        var identity = new UserIdentity("firebase", "uid-1");
        var created = createUser(identity, createProfileInput("user@example.com", "Alex", UTC));

        userPersistence.softDelete(created.id()).get(5, SECONDS);
        // a second soft-delete on an already-deleted user must be a no-op, not throw
        userPersistence.softDelete(created.id()).get(5, SECONDS);

        assertThat(userPersistence.getById(created.id()).get(5, SECONDS)).isEmpty();
    }

    @Test
    void restoreRevivesOnlyIdentitiesSoftDeletedWithTheAccount() throws Exception {
        var clock = new ProgrammableClock();
        clock.setTime(Instant.parse("2026-01-01T00:00:00Z"));
        startUserPersistence(dataSourceFactory, List.of(), clock);

        var firebase = new UserIdentity("firebase", "uid-1");
        var google = new UserIdentity("google.com", "g-1");
        var created = createUser(firebase, createProfileInput("user@example.com", "Alex", UTC));
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
        var created = createUser(identity, createProfileInput("user@example.com", "Alex", UTC));

        userPersistence.restore(created.id()).get(5, SECONDS); // not soft-deleted: no-op

        assertThat(userPersistence.getById(created.id()).get(5, SECONDS)).isPresent();
    }

    @Test
    void rejectsUpdatingIdentityToAnotherUser() throws Exception {
        startUserPersistence(dataSourceFactory, List.of());
        var identity = new UserIdentity("firebase", "uid-1");
        var profileInput = createProfileInput("user@example.com", "Alex", UTC);
        createUser(identity, profileInput);

        var otherIdentity = new UserIdentity("google.com", "uid-2");
        var otherProfile = createProfileInput("other@example.com", "Morgan", UTC);
        var user2 = createUser(otherIdentity, otherProfile);

        assertThatThrownBy(() -> userPersistence.updateAllIdentities(user2.id(), List.of(otherIdentity, identity)).get(5, SECONDS))
                .hasRootCauseInstanceOf(IllegalStateException.class);
    }


    /// Returns the profile for `identity`, creating the user if needed; fails the test if the store reports an email conflict.
    private UserProfile createUser(UserIdentity identity, UserProfileInput profile) throws Exception {
        var creation = userPersistence.getOrCreateByIdentity(identity, profile).get(5, SECONDS);
        return switch (creation) {
            case UserCreationResult.Resolved(var profileResult) -> profileResult;
            case UserCreationResult.EmailAlreadyInUse ignored -> throw new AssertionError("expected Resolved, got EmailAlreadyInUse");
        };
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

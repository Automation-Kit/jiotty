package net.yudichev.jiotty.user.persistence;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSetMultimap;
import jakarta.inject.Provider;
import net.yudichev.jiotty.common.async.ListenerBackedTaskExceptionHandlerRegistry;
import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.async.RejectingSchedulingExecutor;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.async.SingleThreadedSchedulingExecutor;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.testutil.LogCapture;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import net.yudichev.jiotty.common.time.TimeProvider;
import net.yudichev.jiotty.persistence.db.CloseableDataSource;
import net.yudichev.jiotty.persistence.db.DataSourceFactory;
import net.yudichev.jiotty.persistence.domain.PersistenceDomain;
import net.yudichev.jiotty.persistence.domain.PersistenceDomainConfig;
import net.yudichev.jiotty.persistence.domain.PersistenceDomainMigrator;
import net.yudichev.jiotty.persistence.domain.PersistenceDomainServiceImpl;
import net.yudichev.jiotty.persistence.test.EmbeddedPostgresExtension;
import net.yudichev.jiotty.persistence.test.UsingEmbeddedPostgres;
import org.apache.logging.log4j.Level;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Optional.of;
import static java.util.concurrent.TimeUnit.SECONDS;
import static net.yudichev.jiotty.common.lang.EvenMoreObjects.mapIfNotNull;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;
import static net.yudichev.jiotty.persistence.domain.PersistenceDomainConfig.DOMAIN_PREFIX_PLACEHOLDER;
import static net.yudichev.jiotty.user.persistence.BaseUserChangeContractTest.IDENTITY;
import static net.yudichev.jiotty.user.persistence.RecordingUserStateListener.Changed;
import static net.yudichev.jiotty.user.persistence.UserPersistence.IdentityResolution;
import static net.yudichev.jiotty.user.persistence.UserPersistence.UserCreationResult;
import static net.yudichev.jiotty.user.persistence.UserPersistenceTestTimeouts.DELIVERY_TIMEOUT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.list;
import static org.assertj.core.api.InstanceOfAssertFactories.optional;

@UsingEmbeddedPostgres
class UserPersistenceImplTest {
    private static final String DOMAIN_NAME = "users";
    private static final String EXTRA_INIT_STATEMENT = "CREATE TABLE IF NOT EXISTS " + DOMAIN_PREFIX_PLACEHOLDER + "extra (id integer);";
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final ZoneId EUROPE_LONDON = ZoneId.of("Europe/London");
    private static final String ACTIVITY_KIND = "CHARGE_STARTED";
    private static final LocalDate ACTIVITY_DAY = LocalDate.of(2026, 3, 1);
    private static final String ATTACHMENT = "devices";
    private static final ListenerBackedTaskExceptionHandlerRegistry failureRegistry = new ListenerBackedTaskExceptionHandlerRegistry();

    @RegisterExtension
    private static final EmbeddedPostgresExtension postgres = new EmbeddedPostgresExtension();
    private DataSource dataSource;
    private DataSourceFactory dataSourceFactory;
    private SingleThreadedSchedulingExecutor executor;
    /// The second instance's executor in the concurrent-creation test. A separate thread is the whole point: one instance's single-threaded executor serialises
    /// its own tasks, so a race can only be staged between two instances.
    private SingleThreadedSchedulingExecutor racingExecutor;
    private RejectingSchedulingExecutor rejectingExecutor;
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
        rejectingExecutor = new RejectingSchedulingExecutor(executor);
        executorProvider = () -> rejectingExecutor;
        domainService = new PersistenceDomainServiceImpl(dataSourceFactory, executorProvider, new ListenerBackedTaskExceptionHandlerRegistry());
        domain = new PersistenceDomain(DOMAIN_NAME);
        domainService.start();
    }

    @AfterEach
    void tearDown() {
        rejectingExecutor.emptyQueue();
        Closeable.closeIfNotNull(userPersistence == null ? null : userPersistence::stop, domainService == null ? null : domainService::stop,
                                 executor, racingExecutor);
    }

    @Test
    void aRequestTheExecutorRejectsFailsItsFuture() {
        startUserPersistence(dataSourceFactory, List.of());
        rejectingExecutor.fillQueue();

        CompletableFuture<List<UserProfile>> listing = userPersistence.listAllProfiles();

        assertThat(listing).failsWithin(Duration.ZERO).withThrowableThat().havingRootCause().isInstanceOf(RejectedExecutionException.class);
    }

    @Test
    void aSubscriptionTheExecutorRejectsThrowsTheRejection() {
        startUserPersistence(dataSourceFactory, List.of());
        rejectingExecutor.fillQueue();

        assertThatThrownBy(() -> userPersistence.subscribe(new RecordingUserStateListener())).isInstanceOf(RejectedExecutionException.class);
    }

    @Test
    void anIdentitySubscriptionTheExecutorRejectsThrowsTheRejection() {
        startUserPersistence(dataSourceFactory, List.of());
        rejectingExecutor.fillQueue();

        assertThatThrownBy(() -> userPersistence.subscribe(IDENTITY, new RecordingIdentityListener())).isInstanceOf(RejectedExecutionException.class);
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
                                                         PersistenceDomainMigrator.FAIL_ON_MIGRATION,
                                                         failureRegistry))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /// The returned profile must equal what a later read produces, which `timestamptz` limits to microseconds — a nanosecond-bearing instant would make the
    /// two differ on any platform whose clock offers that resolution.
    @Test
    void createdProfileTimestampsMatchWhatIsPersisted() throws Exception {
        startUserPersistence(dataSourceFactory, List.of(EXTRA_INIT_STATEMENT));
        var identity = new UserIdentity("firebase", "uid-precision");

        var created = createUser(identity, createProfileInput("precision@example.com", "Precision", UTC));

        assertThat(created.createdAt()).isEqualTo(created.createdAt().truncatedTo(ChronoUnit.MICROS));
        assertThat(userPersistence.getByIdentity(identity).get(5, SECONDS)).contains(created);
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
        dropUserTable();

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
        UserProfile createdProfile = createUser();
        assertThat(userPersistence.softDelete(createdProfile.id())).succeedsWithin(DELIVERY_TIMEOUT);

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
                                            domain.name(), List.of(), PersistenceDomainMigrator.FAIL_ON_MIGRATION, failureRegistry) {
            @Override
            boolean onBeforeInsertingNewUser() {
                atRaceWindow.countDown();
                return getAsUnchecked(() -> competitorCommitted.await(10, SECONDS));
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

        // The racer loses the insert and adopts the winner's user rather than failing or creating a second one — and reports created=false, which lets
        // a caller do something once per account rather than once per resolution.
        assertThat(racerCreation).succeedsWithin(Duration.ofSeconds(10)).isEqualTo(new UserCreationResult.Resolved(winner, false));
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
    void existsIgnoringDeletionSeesSoftDeletedButNotHardDeletedUsers() {
        startUserPersistence(dataSourceFactory, List.of());
        UserProfile createdProfile = createUser();

        assertThat(userPersistence.existsIgnoringDeletion(createdProfile.id())).succeedsWithin(DELIVERY_TIMEOUT).isEqualTo(true);
        assertThat(userPersistence.existsIgnoringDeletion("u-does-not-exist")).succeedsWithin(DELIVERY_TIMEOUT).isEqualTo(false);

        assertThat(userPersistence.softDelete(createdProfile.id())).succeedsWithin(DELIVERY_TIMEOUT);
        assertThat(userPersistence.existsIgnoringDeletion(createdProfile.id())).succeedsWithin(DELIVERY_TIMEOUT).isEqualTo(true);

        assertThat(userPersistence.hardDelete(createdProfile.id())).succeedsWithin(DELIVERY_TIMEOUT);
        assertThat(userPersistence.existsIgnoringDeletion(createdProfile.id())).succeedsWithin(DELIVERY_TIMEOUT).isEqualTo(false);
    }

    @Test
    void failsListingProfilesIgnoringDeletionWhenUserTableMissing() throws Exception {
        startUserPersistence(dataSourceFactory, List.of());
        dropUserTable();

        assertThatThrownBy(() -> userPersistence.listAllProfilesIgnoringDeletion().get(5, SECONDS))
                .hasCauseInstanceOf(RuntimeException.class)
                .hasRootCauseInstanceOf(SQLException.class);
    }

    @Test
    void failsWhenConnectionUnavailable() {
        ToggleableDataSource toggleableDataSource = startUserPersistenceOnToggleableDataSource();
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

        executeSql("DROP TABLE " + UserPersistenceImpl.IDENTITY_TABLE_TEMPLATE);

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

    /// `created` separates "this call inserted the row" from "the row was already there", which a caller doing something once per account — sending a welcome
    /// email, raising a registration alert — needs and the variant alone does not give it.
    @Test
    void reportsWhetherTheCallActuallyCreatedTheUser() throws Exception {
        startUserPersistence(dataSourceFactory, List.of());
        var identity = new UserIdentity("firebase", "uid-1");
        var profileInput = createProfileInput("user@example.com", "Alex", UTC);

        assertThat(userPersistence.getOrCreateByIdentity(identity, profileInput).get(5, SECONDS))
                .isInstanceOfSatisfying(UserCreationResult.Resolved.class, resolved -> assertThat(resolved.created()).isTrue());
        assertThat(userPersistence.getOrCreateByIdentity(identity, profileInput).get(5, SECONDS))
                .isInstanceOfSatisfying(UserCreationResult.Resolved.class, resolved -> assertThat(resolved.created()).isFalse());
    }

    /// The soft-deleted window is the whole reason this read exists: an account scheduled for deletion is soft-deleted at once, and anything that still has to
    /// reach its owner before erasure — the deletion-scheduled and account-erased emails — can no longer find them through getById.
    @Test
    void getByIdIgnoringDeletionSeesASoftDeletedUserThatGetByIdDoesNot() throws Exception {
        startUserPersistence(dataSourceFactory, List.of());
        var identity = new UserIdentity("firebase", "uid-1");
        var created = createUser(identity, createProfileInput("user@example.com", "Alex", UTC));

        userPersistence.softDelete(created.id()).get(5, SECONDS);

        assertThat(userPersistence.getById(created.id()).get(5, SECONDS)).isEmpty();
        assertThat(userPersistence.getByIdIgnoringDeletion(created.id()).get(5, SECONDS))
                .hasValueSatisfying(profile -> assertThat(profile.email()).contains("user@example.com"));
    }

    @Test
    void getByIdIgnoringDeletionIsEmptyOnceHardDeleted() throws Exception {
        startUserPersistence(dataSourceFactory, List.of());
        var identity = new UserIdentity("firebase", "uid-1");
        var created = createUser(identity, createProfileInput("user@example.com", "Alex", UTC));

        userPersistence.hardDelete(created.id()).get(5, SECONDS);

        assertThat(userPersistence.getByIdIgnoringDeletion(created.id()).get(5, SECONDS)).isEmpty();
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

    /// A soft-deleted user keeps the identities they had when their deletion was requested, and a user with no row has none to update.
    @Test
    void refusesToUpdateTheIdentitiesOfASoftDeletedOrMissingUser() throws Exception {
        startUserPersistence(dataSourceFactory, List.of());
        var identity = new UserIdentity("firebase", "uid-1");
        UserProfile createdProfile = createUser(identity, createProfileInput("user@example.com", "Alex", UTC));
        assertThat(userPersistence.softDelete(createdProfile.id())).succeedsWithin(DELIVERY_TIMEOUT);
        var googleIdentity = new UserIdentity("google.com", "uid-g");

        assertThat(userPersistence.updateAllIdentities(createdProfile.id(), List.of(identity, googleIdentity)))
                .failsWithin(Duration.ofSeconds(5)).withThrowableThat().havingRootCause().isInstanceOf(IllegalStateException.class);
        assertThat(userPersistence.updateAllIdentities("u-does-not-exist", List.of(identity)))
                .failsWithin(Duration.ofSeconds(5)).withThrowableThat().havingRootCause().isInstanceOf(IllegalStateException.class);
    }

    /// A soft delete or restore of a user with no row changes nothing, so it announces nothing.
    @Test
    void aWriteToAUserWithNoRowAnnouncesNothing() throws Exception {
        startUserPersistence(dataSourceFactory, List.of());
        var stateListener = new RecordingUserStateListener();
        userPersistence.subscribe(stateListener);
        stateListener.awaitImage();

        userPersistence.softDelete("u-does-not-exist").get(5, SECONDS);
        userPersistence.restore("u-does-not-exist").get(5, SECONDS);

        assertThat(stateListener.changes()).isEmpty();
    }

    /// A soft delete that cannot read the state it leaves fails, leaves the user as they were, and announces nothing.
    @Test
    void aWriteWhoseResultingStateCannotBeReadFailsAndChangesNothing() {
        ToggleableDataSource toggleableDataSource = startUserPersistenceOnToggleableDataSource();
        UserProfile createdProfile = createUser();
        var stateListener = new RecordingUserStateListener();
        userPersistence.subscribe(stateListener);
        stateListener.awaitImage();
        toggleableDataSource.setFailStatementsContaining("deleted_at FROM");

        assertThat(userPersistence.softDelete(createdProfile.id())).failsWithin(DELIVERY_TIMEOUT)
                                                                   .withThrowableThat()
                                                                   .havingRootCause()
                                                                   .withMessage(ToggleableDataSource.STATEMENT_FAILURE);

        toggleableDataSource.setFailStatementsContaining(null);
        assertThat(stateListener.changes()).isEmpty();
        assertThat(userPersistence.getById(createdProfile.id())).succeedsWithin(DELIVERY_TIMEOUT).isEqualTo(Optional.of(createdProfile));
    }

    /// A subscriber whose first delivery cannot be read is told so, and receives no change after it.
    @Test
    void aSubscriptionWhoseFirstDeliveryCannotBeReadIsToldSoAndReceivesNothingAfter() throws Exception {
        ToggleableDataSource toggleableDataSource = startUserPersistenceOnToggleableDataSource();
        var identity = new UserIdentity("firebase", "uid-1");
        var stateListener = new RecordingUserStateListener();
        var identityListener = new RecordingIdentityListener();
        toggleableDataSource.setFailConnections(true);

        userPersistence.subscribe(stateListener);
        userPersistence.subscribe(identity, identityListener);

        assertThat(stateListener.image()).failsWithin(DELIVERY_TIMEOUT).withThrowableThat().havingRootCause().isInstanceOf(SQLException.class);
        assertThat(identityListener.firstResolution()).failsWithin(DELIVERY_TIMEOUT)
                                                      .withThrowableThat()
                                                      .havingRootCause()
                                                      .isInstanceOf(SQLException.class);
        toggleableDataSource.setFailConnections(false);
        createUser(identity, createProfileInput("user@example.com", "Alex", UTC));
        assertThat(identityListener.resolutions()).isEmpty();
    }

    /// A commit can take effect on the server and still report a failure to the client, so what the database then holds is read afresh and announced to both
    /// kinds of subscriber before the failure reaches the caller.
    @Test
    void aCommitReportingAFailureStillAnnouncesWhatTheDatabaseHolds() throws Exception {
        ToggleableDataSource toggleableDataSource = startUserPersistenceOnToggleableDataSource();
        var identity = new UserIdentity("firebase", "uid-1");
        UserProfile createdProfile = createUser(identity, createProfileInput("user@example.com", "Alex", UTC));
        var stateListener = new RecordingUserStateListener();
        userPersistence.subscribe(stateListener);
        var identityListener = new RecordingIdentityListener();
        userPersistence.subscribe(identity, identityListener);
        identityListener.awaitFirst();
        toggleableDataSource.setCommitsTakeEffectButReportFailure(true);

        assertThat(userPersistence.softDelete(createdProfile.id())).failsWithin(DELIVERY_TIMEOUT)
                                                                   .withThrowableThat()
                                                                   .havingRootCause()
                                                                   .withMessage(ToggleableDataSource.EFFECTIVE_COMMIT_FAILURE);

        toggleableDataSource.setCommitsTakeEffectButReportFailure(false);
        assertThat(stateListener.changes()).singleElement()
                                           .isInstanceOfSatisfying(Changed.class, changed -> assertThat(changed.user().deletedAt()).isPresent());
        assertThat(identityListener.resolutions()).last().isInstanceOf(IdentityResolution.SoftDeleted.class);
        assertThat(identityListener.failures()).isEmpty();
    }

    @Test
    void reportsACommitReportingAFailureWhoseOutcomeCannotBeReadBack() {
        ToggleableDataSource toggleableDataSource = startUserPersistenceOnToggleableDataSource();
        UserProfile createdProfile = createUser();
        var stateListener = new RecordingUserStateListener();
        userPersistence.subscribe(stateListener);
        // Synchronised: the persistence thread reports into it, and the test thread reads it.
        var reportedFailures = Collections.synchronizedList(new ArrayList<String>());
        try (Closeable _ = failureRegistry.addExceptionHandler((description, _) -> reportedFailures.add(description))) {
            toggleableDataSource.setCommitsTakeEffectButReportFailure(true);
            toggleableDataSource.setUnreachableOnceACommitReportsFailure(true);

            assertThat(userPersistence.softDelete(createdProfile.id())).failsWithin(DELIVERY_TIMEOUT)
                                                                       .withThrowableThat()
                                                                       .havingRootCause()
                                                                       .withMessage(ToggleableDataSource.EFFECTIVE_COMMIT_FAILURE);

            assertThat(reportedFailures).contains(UserPersistenceImpl.FAILED_COMMIT_READ_BACK_TASK);
            assertThat(stateListener.changes()).isEmpty();
        }
    }

    /// When the commit of a record write takes effect and reports a failure, the store reads the user's record again and delivers it to the record
    /// subscribers before the write's future fails.
    @Test
    void aRecordCommitReportingAFailureStillDeliversWhatTheDatabaseHolds() {
        ToggleableDataSource toggleableDataSource = startUserPersistenceOnToggleableDataSource();
        UserProfile createdProfile = createUser();
        RecordingUserRecordListener recordListener = subscribeRecordListener();
        toggleableDataSource.setCommitsTakeEffectButReportFailure(true);

        assertThat(userPersistence.recordActivity(createdProfile.id(), ACTIVITY_KIND, ACTIVITY_DAY)).failsWithin(DELIVERY_TIMEOUT)
                                                                                                    .withThrowableThat()
                                                                                                    .havingRootCause()
                                                                                                    .withMessage(ToggleableDataSource.EFFECTIVE_COMMIT_FAILURE);

        toggleableDataSource.setCommitsTakeEffectButReportFailure(false);
        assertThat(recordListener.changes()).singleElement().isEqualTo(new RecordingUserRecordListener.Changed(
                new UserRecord(createdProfile.id(), ImmutableSetMultimap.of(ACTIVITY_KIND, ACTIVITY_DAY), false, Map.of())));
    }

    /// When the commit of a record write takes effect and reports a failure, and the store then fails to read the user's record again, the store ends the
    /// record subscriptions, so that a new subscription delivers a current image. The state subscriptions continue.
    @Test
    void aRecordCommitReportingAFailureThatCannotBeReadBackEndsTheRecordSubscriptions() {
        ToggleableDataSource toggleableDataSource = startUserPersistenceOnToggleableDataSource();
        UserProfile createdProfile = createUser();
        RecordingUserRecordListener recordListener = subscribeRecordListener();
        var stateListener = new RecordingUserStateListener();
        userPersistence.subscribe(stateListener);
        // The persistence thread adds each reported failure to `reportedFailures`, and the test thread reads the list, so the list is synchronised.
        var reportedFailures = Collections.synchronizedList(new ArrayList<String>());
        try (Closeable _ = failureRegistry.addExceptionHandler((description, _) -> reportedFailures.add(description))) {
            toggleableDataSource.setCommitsTakeEffectButReportFailure(true);
            toggleableDataSource.setUnreachableOnceACommitReportsFailure(true);

            assertThat(userPersistence.putAttachment(createdProfile.id(), ATTACHMENT, "[]")).failsWithin(DELIVERY_TIMEOUT);

            assertThat(recordListener.failure()).succeedsWithin(DELIVERY_TIMEOUT).isInstanceOf(SQLException.class);
            assertThat(reportedFailures).contains(UserPersistenceImpl.FAILED_COMMIT_READ_BACK_TASK);
            assertThat(recordListener.changes()).isEmpty();
        }
        toggleableDataSource.setCommitsTakeEffectButReportFailure(false);
        toggleableDataSource.setFailConnections(false);
        assertThat(userPersistence.softDelete(createdProfile.id())).succeedsWithin(DELIVERY_TIMEOUT);
        assertThat(stateListener.changes()).as("the state subscription is unaffected").hasSize(1);
    }

    /// [UserPersistence#purgeActivityDaysBefore] deletes the days of every user in one commit. When that commit reports a failure, the store has no single
    /// user's record to read again, so it ends the record subscriptions.
    @Test
    void aPurgeCommitReportingAFailureEndsTheRecordSubscriptions() {
        ToggleableDataSource toggleableDataSource = startUserPersistenceOnToggleableDataSource();
        UserProfile createdProfile = createUser();
        assertThat(userPersistence.recordActivity(createdProfile.id(), ACTIVITY_KIND, ACTIVITY_DAY)).succeedsWithin(DELIVERY_TIMEOUT);
        RecordingUserRecordListener recordListener = subscribeRecordListener();
        toggleableDataSource.setCommitsTakeEffectButReportFailure(true);

        assertThat(userPersistence.purgeActivityDaysBefore(ACTIVITY_DAY.plusDays(1))).failsWithin(DELIVERY_TIMEOUT);

        toggleableDataSource.setCommitsTakeEffectButReportFailure(false);
        assertThat(recordListener.failure()).succeedsWithin(DELIVERY_TIMEOUT).isInstanceOf(SQLException.class);
        assertThat(recordListener.changes()).isEmpty();
    }

    @Test
    void aRecordCommitReportingAFailureAfterAWriteThatChangedNothingDeliversNothing() {
        ToggleableDataSource toggleableDataSource = startUserPersistenceOnToggleableDataSource();
        UserProfile createdProfile = createUser();
        assertThat(userPersistence.putAttachment(createdProfile.id(), ATTACHMENT, "[]")).succeedsWithin(DELIVERY_TIMEOUT);
        RecordingUserRecordListener recordListener = subscribeRecordListener();
        toggleableDataSource.setCommitsTakeEffectButReportFailure(true);

        assertThat(userPersistence.putAttachment(createdProfile.id(), ATTACHMENT, "[]")).failsWithin(DELIVERY_TIMEOUT);

        toggleableDataSource.setCommitsTakeEffectButReportFailure(false);
        assertThat(userPersistence.putAttachment(createdProfile.id(), ATTACHMENT, "[1]")).succeedsWithin(DELIVERY_TIMEOUT);
        assertThat(recordListener.changes()).as("only the write that changed the record is delivered").hasSize(1);
    }

    /// [UserPersistenceImpl#setActivityHistoryDisabled] logs an INFO line with the user's internal id and the new state when the call changes the state. A
    /// call that leaves the state unchanged logs nothing.
    @Test
    void switchingActivityHistoryLogsOnlyAChangeOfState() {
        startUserPersistence(dataSourceFactory, List.of());
        String userId = createUser().id();

        try (LogCapture log = LogCapture.capture(UserPersistenceImpl.class, Level.INFO)) {
            assertThat(userPersistence.setActivityHistoryDisabled(userId, false)).succeedsWithin(DELIVERY_TIMEOUT);
            assertThat(userPersistence.setActivityHistoryDisabled(userId, true)).succeedsWithin(DELIVERY_TIMEOUT);
            assertThat(userPersistence.setActivityHistoryDisabled(userId, true)).succeedsWithin(DELIVERY_TIMEOUT);
            assertThat(userPersistence.setActivityHistoryDisabled(userId, false)).succeedsWithin(DELIVERY_TIMEOUT);

            assertThat(log.lines()).map(LogCapture.CapturedLine::message)
                                   .filteredOn(message -> message.contains("Activity history"))
                                   .satisfiesExactly(message -> assertThat(message).startsWith('[' + userId + "] Activity history disabled"),
                                                     message -> assertThat(message).isEqualTo('[' + userId + "] Activity history enabled"));
        }
    }

    @Test
    void aRecordReadOrAnActivityWriteFailsWhenNoConnectionIsAvailable() {
        ToggleableDataSource toggleableDataSource = startUserPersistenceOnToggleableDataSource();
        toggleableDataSource.setFailConnections(true);

        assertThat(userPersistence.readRecord("user-1")).failsWithin(DELIVERY_TIMEOUT).withThrowableThat().havingRootCause().isInstanceOf(SQLException.class);
        assertThat(userPersistence.recordActivity("user-1", ACTIVITY_KIND, ACTIVITY_DAY)).failsWithin(DELIVERY_TIMEOUT)
                                                                                         .withThrowableThat()
                                                                                         .havingRootCause()
                                                                                         .isInstanceOf(SQLException.class);
        assertThat(userPersistence.touchLastActive("user-1", ACTIVITY_DAY.atStartOfDay(UTC).toInstant())).failsWithin(DELIVERY_TIMEOUT)
                                                                                                         .withThrowableThat()
                                                                                                         .havingRootCause()
                                                                                                         .isInstanceOf(SQLException.class);
    }

    @Test
    void aRecordSubscriptionWhoseImageCannotBeReadIsToldSo() {
        ToggleableDataSource toggleableDataSource = startUserPersistenceOnToggleableDataSource();
        var recordListener = new RecordingUserRecordListener();
        toggleableDataSource.setFailConnections(true);

        userPersistence.subscribe(ACTIVITY_DAY, recordListener);

        assertThat(recordListener.image()).failsWithin(DELIVERY_TIMEOUT).withThrowableThat().havingRootCause().isInstanceOf(SQLException.class);
    }

    @Test
    void aRecordSubscriptionTheExecutorRejectsThrowsTheRejection() {
        startUserPersistence(dataSourceFactory, List.of());
        rejectingExecutor.fillQueue();

        assertThatThrownBy(() -> userPersistence.subscribe(ACTIVITY_DAY, new RecordingUserRecordListener())).isInstanceOf(RejectedExecutionException.class);
    }

    /// A store created at version 3 holds users, and lacks the column and the tables that version 4 adds. After the store's migration to version 4:
    ///
    /// - each existing user's activity history is enabled;
    /// - each user can hold activity days and attachments;
    /// - the `user_id` column of each new table references the user table, as the `user_id` column of the identity table does;
    /// - the store has the columns, defaults and keys that [#assertHoldsTheVersion4Schema()] checks, as a store created at the current version has them.
    @Test
    void migratesAPopulatedVersion3StoreToVersion4() throws SQLException {
        startUserPersistence(dataSourceFactory, List.of(), new TimeProvider(), 3, PersistenceDomainMigrator.FAIL_ON_MIGRATION);
        UserProfile existingProfile = createUser();
        userPersistence.stop();
        executeSql("DROP TABLE " + UserPersistenceImpl.ACTIVITY_DAY_TABLE_TEMPLATE);
        executeSql("DROP TABLE " + UserPersistenceImpl.ATTACHMENT_TABLE_TEMPLATE);
        executeSql("ALTER TABLE " + UserPersistenceImpl.USER_TABLE_TEMPLATE + " DROP COLUMN " + UserPersistenceImpl.ACTIVITY_HISTORY_DISABLED_COLUMN);

        startUserPersistence(dataSourceFactory, List.of(), new TimeProvider(), UserSchemaMigrator.BASE_SCHEMA_VERSION, new UserSchemaMigrator());

        assertThat(userPersistence.recordActivity(existingProfile.id(), ACTIVITY_KIND, ACTIVITY_DAY)).succeedsWithin(DELIVERY_TIMEOUT)
                                                                                                     .isEqualTo(ActivityRecordOutcome.RECORDED);
        assertThat(userPersistence.putAttachment(existingProfile.id(), ATTACHMENT, "[]")).succeedsWithin(DELIVERY_TIMEOUT).isEqualTo(true);
        assertThat(userPersistence.readRecord(existingProfile.id())).succeedsWithin(DELIVERY_TIMEOUT)
                                                                    .asInstanceOf(optional(UserRecord.class))
                                                                    .hasValue(new UserRecord(existingProfile.id(),
                                                                                             ImmutableSetMultimap.of(ACTIVITY_KIND, ACTIVITY_DAY),
                                                                                             false,
                                                                                             Map.of(ATTACHMENT, "[]")));
        assertThat(userPersistence.getById(existingProfile.id())).succeedsWithin(DELIVERY_TIMEOUT)
                                                                 .asInstanceOf(optional(UserProfile.class))
                                                                 .hasValue(existingProfile);
        assertThat(listTablesReferencingTheUserTable()).containsExactlyInAnyOrder(UserPersistenceImpl.IDENTITY_TABLE,
                                                                                  UserPersistenceImpl.ACTIVITY_DAY_TABLE,
                                                                                  UserPersistenceImpl.ATTACHMENT_TABLE);
        assertHoldsTheVersion4Schema();
    }

    /// A store created at the current version has the same foreign keys to the user table as a store migrated to that version, and the same columns, defaults
    /// and keys that [#assertHoldsTheVersion4Schema()] checks.
    @Test
    void aStoreCreatedAtTheCurrentVersionHoldsTheVersion4ColumnsAndKeys() throws SQLException {
        startUserPersistence(dataSourceFactory, List.of());

        assertThat(listTablesReferencingTheUserTable()).containsExactlyInAnyOrder(UserPersistenceImpl.IDENTITY_TABLE,
                                                                                  UserPersistenceImpl.ACTIVITY_DAY_TABLE,
                                                                                  UserPersistenceImpl.ATTACHMENT_TABLE);
        assertHoldsTheVersion4Schema();
    }

    /// A row starts current rather than at the epoch, so a selection by activity never returns a user who has simply not been touched yet — and it starts on
    /// the day of creation rather than at its instant, because nothing may write a time of day into this column.
    @Test
    void aNewUserIsActiveAsOfTheDayOfCreation() throws Exception {
        var clock = new ProgrammableClock();
        clock.setTime(Instant.parse("2026-03-01T10:00:00Z"));
        startUserPersistence(dataSourceFactory, List.of(), clock);
        var created = createUser(new UserIdentity("firebase", "uid-fresh"), createProfileInput("fresh@example.com", "Fresh", UTC));
        Instant dayOfCreation = created.createdAt().truncatedTo(ChronoUnit.DAYS);

        assertThat(userPersistence.listInactiveSince(dayOfCreation, 100).get(5, SECONDS)).isEmpty();
        assertThat(userPersistence.listInactiveSince(dayOfCreation.plusMillis(1), 100).get(5, SECONDS))
                .extracting(UserProfile::id).containsExactly(created.id());
    }

    /// The column only ever moves forward, so a late-delivered sighting cannot make a live account look dormant.
    @Test
    void touchLastActiveNeverMovesTheInstantBackwards() throws Exception {
        var clock = new ProgrammableClock();
        clock.setTime(Instant.parse("2026-03-01T10:00:00Z"));
        startUserPersistence(dataSourceFactory, List.of(), clock);
        var created = createUser(new UserIdentity("firebase", "uid-touch"), createProfileInput("touch@example.com", "Touch", UTC));
        var later = created.createdAt().plus(Duration.ofDays(10));

        assertThat(userPersistence.touchLastActive(created.id(), later).get(5, SECONDS)).isTrue();
        assertThat(userPersistence.touchLastActive(created.id(), later).get(5, SECONDS)).isFalse();
        assertThat(userPersistence.touchLastActive(created.id(), created.createdAt()).get(5, SECONDS)).isFalse();
        assertThat(userPersistence.listInactiveSince(later, 100).get(5, SECONDS)).isEmpty();
    }

    /// Both operations exclude an already soft-deleted user, so neither can act twice on the same deletion.
    @Test
    void aSoftDeletedUserIsNotListedAsInactiveAndCannotBeDeletedAgain() throws Exception {
        var clock = new ProgrammableClock();
        clock.setTime(Instant.parse("2026-03-01T10:00:00Z"));
        startUserPersistence(dataSourceFactory, List.of(), clock);
        var created = createUser(new UserIdentity("firebase", "uid-gone"), createProfileInput("gone@example.com", "Gone", UTC));
        userPersistence.softDelete(created.id()).get(5, SECONDS);
        var cutoff = created.createdAt().plus(Duration.ofDays(1));

        assertThat(userPersistence.listInactiveSince(cutoff, 100).get(5, SECONDS)).isEmpty();
        // ALREADY_SOFT_DELETED, not STILL_ACTIVE: nothing here says anything about this user's activity, and a caller re-asserting a delete it already made
        // relies on being able to tell the two apart.
        assertThat(userPersistence.softDeleteIfInactiveSince(created.id(), cutoff).get(5, SECONDS))
                .isEqualTo(ConditionalSoftDeleteOutcome.ALREADY_SOFT_DELETED);
    }

    /// The one outcome that means the user's own activity spared them, which is the only one a caller may read as "leave this account alone".
    @Test
    void softDeleteIfInactiveSinceReportsWhyItDidNothing() throws Exception {
        var clock = new ProgrammableClock();
        clock.setTime(Instant.parse("2026-03-01T10:00:00Z"));
        startUserPersistence(dataSourceFactory, List.of(), clock);
        var created = createUser(new UserIdentity("firebase", "uid-outcomes"), createProfileInput("outcomes@example.com", "Outcomes", UTC));
        var cutoff = created.createdAt().plus(Duration.ofDays(365));

        assertThat(userPersistence.softDeleteIfInactiveSince("no-such-user", cutoff).get(5, SECONDS))
                .isEqualTo(ConditionalSoftDeleteOutcome.ABSENT);

        userPersistence.touchLastActive(created.id(), cutoff.plus(Duration.ofDays(1))).get(5, SECONDS);
        assertThat(userPersistence.softDeleteIfInactiveSince(created.id(), cutoff).get(5, SECONDS))
                .isEqualTo(ConditionalSoftDeleteOutcome.STILL_ACTIVE);
    }

    /// A soft-deleted user may still be restored, so activity recorded while they were deleted has to survive the restore — otherwise the revived row
    /// understates when it was last active and a selection by activity picks it up again immediately.
    @Test
    void activityRecordedWhileSoftDeletedSurvivesTheRestore() throws Exception {
        var clock = new ProgrammableClock();
        clock.setTime(Instant.parse("2026-03-01T10:00:00Z"));
        startUserPersistence(dataSourceFactory, List.of(), clock);
        var created = createUser(new UserIdentity("firebase", "uid-returner-2"), createProfileInput("returner2@example.com", "Returner", UTC));
        var cutoff = created.createdAt().plus(Duration.ofDays(365));
        userPersistence.softDelete(created.id()).get(5, SECONDS);

        assertThat(userPersistence.touchLastActive(created.id(), cutoff.plus(Duration.ofDays(1))).get(5, SECONDS)).isTrue();
        userPersistence.restore(created.id()).get(5, SECONDS);

        // The revived row carries the activity recorded while it was deleted, so neither operation sees it as inactive.
        assertThat(userPersistence.listInactiveSince(cutoff, 100).get(5, SECONDS)).isEmpty();
        assertThat(userPersistence.softDeleteIfInactiveSince(created.id(), cutoff).get(5, SECONDS))
                .isEqualTo(ConditionalSoftDeleteOutcome.STILL_ACTIVE);
    }

    /// The reason the conditional variant exists: activity landing after the selection and before the delete must win, which a separate read then
    /// [UserPersistence#softDelete] cannot guarantee.
    /// The limit bounds the read, and the ordering decides which rows survive it: a caller sweeping the least recently active first must get exactly those,
    /// or a cutoff that matches everybody would both cost a whole-table read and hand back an arbitrary slice of it.
    @Test
    void listInactiveSinceReturnsNoMoreThanTheLimitAndTakesTheLeastRecentlyActiveFirst() throws Exception {
        var clock = new ProgrammableClock();
        clock.setTime(Instant.parse("2026-03-01T10:00:00Z"));
        startUserPersistence(dataSourceFactory, List.of(), clock);
        var first = createUser(new UserIdentity("firebase", "uid-a"), createProfileInput("a@example.com", "A", UTC));
        var second = createUser(new UserIdentity("firebase", "uid-b"), createProfileInput("b@example.com", "B", UTC));
        var third = createUser(new UserIdentity("firebase", "uid-c"), createProfileInput("c@example.com", "C", UTC));
        // Distinct activity days, so "least recently active" is a total order rather than a tie.
        Instant day = first.createdAt().truncatedTo(ChronoUnit.DAYS);
        userPersistence.touchLastActive(second.id(), day.plus(Duration.ofDays(1))).get(5, SECONDS);
        userPersistence.touchLastActive(third.id(), day.plus(Duration.ofDays(2))).get(5, SECONDS);
        var cutoff = day.plus(Duration.ofDays(10));

        assertThat(userPersistence.listInactiveSince(cutoff, 2).get(5, SECONDS))
                .extracting(UserProfile::id)
                .containsExactly(first.id(), second.id());
        assertThat(userPersistence.listInactiveSince(cutoff, 100).get(5, SECONDS))
                .extracting(UserProfile::id)
                .containsExactly(first.id(), second.id(), third.id());
    }

    @Test
    void softDeleteIfInactiveSinceSparesAUserWhoBecameActive() throws Exception {
        var clock = new ProgrammableClock();
        clock.setTime(Instant.parse("2026-03-01T10:00:00Z"));
        startUserPersistence(dataSourceFactory, List.of(), clock);
        var identity = new UserIdentity("firebase", "uid-returner");
        var created = createUser(identity, createProfileInput("returner@example.com", "Returner", UTC));
        var cutoff = created.createdAt().plus(Duration.ofDays(365));
        // Listed as inactive, then touched before the conditional delete is issued.
        assertThat(userPersistence.listInactiveSince(cutoff, 100).get(5, SECONDS)).extracting(UserProfile::id).containsExactly(created.id());
        userPersistence.touchLastActive(created.id(), cutoff.plus(Duration.ofDays(1))).get(5, SECONDS);

        assertThat(userPersistence.softDeleteIfInactiveSince(created.id(), cutoff).get(5, SECONDS))
                .isEqualTo(ConditionalSoftDeleteOutcome.STILL_ACTIVE);
        assertThat(userPersistence.getById(created.id()).get(5, SECONDS)).isPresent();
    }

    /// When it does fire it must leave exactly the state [UserPersistence#softDelete] leaves, identity rows included, so nothing downstream can observe the
    /// two as different kinds of deletion.
    @Test
    void softDeleteIfInactiveSinceDeletesTheUserAndItsIdentities() throws Exception {
        var clock = new ProgrammableClock();
        clock.setTime(Instant.parse("2026-03-01T10:00:00Z"));
        startUserPersistence(dataSourceFactory, List.of(), clock);
        var identity = new UserIdentity("firebase", "uid-idle");
        var created = createUser(identity, createProfileInput("idle@example.com", "Idle", UTC));
        var cutoff = created.createdAt().plus(Duration.ofDays(365));

        assertThat(userPersistence.softDeleteIfInactiveSince(created.id(), cutoff).get(5, SECONDS))
                .isEqualTo(ConditionalSoftDeleteOutcome.SOFT_DELETED);

        assertThat(userPersistence.getById(created.id()).get(5, SECONDS)).isEmpty();
        assertThat(userPersistence.getByIdIgnoringDeletion(created.id()).get(5, SECONDS)).isPresent();
        assertThat(userPersistence.listIdentities(created.id()).get(5, SECONDS)).isEmpty();
        assertThat(userPersistence.resolveByIdentity(identity).get(5, SECONDS)).isInstanceOf(UserPersistence.IdentityResolution.SoftDeleted.class);
        // A second call reports that the account had already gone, which is a different fact from its owner having come back.
        assertThat(userPersistence.softDeleteIfInactiveSince(created.id(), cutoff).get(5, SECONDS))
                .isEqualTo(ConditionalSoftDeleteOutcome.ALREADY_SOFT_DELETED);
    }

    /// Starts the store on a new [ToggleableDataSource] and returns the data source, so that the test can switch its failures on and off.
    private ToggleableDataSource startUserPersistenceOnToggleableDataSource() {
        var toggleableDataSource = new ToggleableDataSource(dataSource);
        startUserPersistence(() -> toggleableDataSource, List.of());
        return toggleableDataSource;
    }

    private RecordingUserRecordListener subscribeRecordListener() {
        var recordListener = new RecordingUserRecordListener();
        userPersistence.subscribe(ACTIVITY_DAY, recordListener);
        return recordListener;
    }

    private UserProfile createUser() {
        return getAsUnchecked(() -> createUser(new UserIdentity("firebase", "uid-1"), createProfileInput("user@example.com", "Alex", UTC)));
    }

    /// Returns the profile for `identity`, creating the user if needed; fails the test if the store reports an email conflict.
    private UserProfile createUser(UserIdentity identity, UserProfileInput profile) throws Exception {
        var creation = userPersistence.getOrCreateByIdentity(identity, profile).get(5, SECONDS);
        return switch (creation) {
            case UserCreationResult.Resolved(var profileResult, _) -> profileResult;
            case UserCreationResult.EmailAlreadyInUse ignored -> throw new AssertionError("expected Resolved, got EmailAlreadyInUse");
        };
    }

    private void startUserPersistence(DataSourceFactory factory, List<String> initStatements) {
        startUserPersistence(factory, initStatements, new TimeProvider());
    }

    private void startUserPersistence(DataSourceFactory factory, List<String> initStatements, CurrentDateTimeProvider timeProvider) {
        startUserPersistence(factory, initStatements, timeProvider, 1, PersistenceDomainMigrator.FAIL_ON_MIGRATION);
    }

    private void startUserPersistence(DataSourceFactory factory,
                                      List<String> initStatements,
                                      CurrentDateTimeProvider timeProvider,
                                      int schemaVersion,
                                      PersistenceDomainMigrator migrator) {
        userPersistence = new UserPersistenceImpl(factory,
                                                  executorProvider,
                                                  domainService,
                                                  timeProvider,
                                                  schemaVersion,
                                                  domain.name(),
                                                  initStatements,
                                                  migrator,
                                                  failureRegistry);
        userPersistence.start();
    }

    private static UserProfileInput createProfileInput(String email, String displayName, ZoneId timezone) {
        return new UserProfileInput(email, of(displayName), timezone);
    }

    private void dropUserTable() throws SQLException {
        executeSql("DROP TABLE " + UserPersistenceImpl.USER_TABLE_TEMPLATE + " CASCADE");
    }

    /// @return the name of each table with a foreign key to the domain's user table, without the domain's prefix
    private List<String> listTablesReferencingTheUserTable() throws SQLException {
        return query("""
                     SELECT DISTINCT constraints.table_name
                     FROM information_schema.table_constraints constraints
                     JOIN information_schema.constraint_column_usage referenced
                       ON referenced.constraint_name = constraints.constraint_name
                      AND referenced.table_schema = constraints.table_schema
                     WHERE constraints.constraint_type = 'FOREIGN KEY' AND referenced.table_name = ?""",
                     resultSet -> withoutDomainPrefix(resultSet.getString(1)),
                     domain.prefix() + UserPersistenceImpl.USER_TABLE);
    }

    /// Asserts that `information_schema` describes each column, default, primary key and foreign key that the version 4 migration declares: the
    /// [UserPersistenceImpl#ACTIVITY_HISTORY_DISABLED_COLUMN] column of the user table, and the columns and keys of the
    /// [UserPersistenceImpl#ACTIVITY_DAY_TABLE] and [UserPersistenceImpl#ATTACHMENT_TABLE] tables.
    private void assertHoldsTheVersion4Schema() throws SQLException {
        String activityDayTable = UserPersistenceImpl.ACTIVITY_DAY_TABLE;
        String attachmentTable = UserPersistenceImpl.ATTACHMENT_TABLE;
        String userIdReference = UserPersistenceImpl.USER_TABLE + "(id)";
        assertThat(query("""
                         SELECT table_name, column_name, data_type, is_nullable = 'YES', column_default
                         FROM information_schema.columns
                         WHERE table_name IN (?, ?) OR (table_name = ? AND column_name = ?)""",
                         resultSet -> new ColumnDescription(withoutDomainPrefix(resultSet.getString(1)),
                                                            resultSet.getString(2),
                                                            resultSet.getString(3),
                                                            resultSet.getBoolean(4),
                                                            resultSet.getString(5)),
                         domain.prefix() + activityDayTable,
                         domain.prefix() + attachmentTable,
                         domain.prefix() + UserPersistenceImpl.USER_TABLE,
                         UserPersistenceImpl.ACTIVITY_HISTORY_DISABLED_COLUMN))
                .containsExactlyInAnyOrder(
                        new ColumnDescription(UserPersistenceImpl.USER_TABLE, UserPersistenceImpl.ACTIVITY_HISTORY_DISABLED_COLUMN, "boolean", false, "false"),
                        new ColumnDescription(activityDayTable, "user_id", "text", false, null),
                        new ColumnDescription(activityDayTable, "day", "date", false, null),
                        new ColumnDescription(activityDayTable, "kind", "text", false, null),
                        new ColumnDescription(attachmentTable, "user_id", "text", false, null),
                        new ColumnDescription(attachmentTable, "name", "text", false, null),
                        new ColumnDescription(attachmentTable, "value", "jsonb", false, null));
        assertThat(query("""
                         SELECT constraints.table_name,
                                constraints.constraint_type,
                                string_agg(keys.column_name, ',' ORDER BY keys.ordinal_position),
                                (SELECT referenced.table_name || '(' || string_agg(referenced.column_name, ',') || ')'
                                 FROM information_schema.constraint_column_usage referenced
                                 WHERE constraints.constraint_type = 'FOREIGN KEY'
                                   AND referenced.constraint_name = constraints.constraint_name
                                   AND referenced.table_schema = constraints.table_schema
                                 GROUP BY referenced.table_name)
                         FROM information_schema.table_constraints constraints
                         JOIN information_schema.key_column_usage keys
                           ON keys.constraint_name = constraints.constraint_name
                          AND keys.table_schema = constraints.table_schema
                          AND keys.table_name = constraints.table_name
                         WHERE constraints.constraint_type IN ('PRIMARY KEY', 'FOREIGN KEY') AND constraints.table_name IN (?, ?)
                         GROUP BY constraints.table_schema, constraints.table_name, constraints.constraint_name, constraints.constraint_type""",
                         resultSet -> new KeyDescription(withoutDomainPrefix(resultSet.getString(1)),
                                                         resultSet.getString(2),
                                                         List.of(resultSet.getString(3).split(",")),
                                                         mapIfNotNull(resultSet.getString(4), this::withoutDomainPrefix)),
                         domain.prefix() + activityDayTable,
                         domain.prefix() + attachmentTable))
                .containsExactlyInAnyOrder(new KeyDescription(activityDayTable, "PRIMARY KEY", List.of("user_id", "day", "kind"), null),
                                           new KeyDescription(activityDayTable, "FOREIGN KEY", List.of("user_id"), userIdReference),
                                           new KeyDescription(attachmentTable, "PRIMARY KEY", List.of("user_id", "name"), null),
                                           new KeyDescription(attachmentTable, "FOREIGN KEY", List.of("user_id"), userIdReference));
    }

    /// @param <T> the type of the value read from each row
    /// @return the values that `rowReader` reads, one from each row that `sql` selects when `parameters` are bound to its placeholders in order
    private <T> List<T> query(String sql, RowReader<T> rowReader, String... parameters) throws SQLException {
        var valuesBuilder = ImmutableList.<T>builder();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setString(i + 1, parameters[i]);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    valuesBuilder.add(rowReader.read(resultSet));
                }
            }
        }
        return valuesBuilder.build();
    }

    private String withoutDomainPrefix(String tableName) {
        assertThat(tableName).startsWith(domain.prefix());
        return tableName.substring(domain.prefix().length());
    }

    /// @param sql a statement whose table names hold [PersistenceDomainConfig#DOMAIN_PREFIX_PLACEHOLDER] in place of the domain's prefix
    private void executeSql(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql.replace(DOMAIN_PREFIX_PLACEHOLDER, domain.prefix()));
        }
    }

    private static final class ToggleableDataSource implements CloseableDataSource {
        static final String EFFECTIVE_COMMIT_FAILURE = "Simulated failure reported by a commit that took effect";
        static final String STATEMENT_FAILURE = "Simulated statement failure";

        private final DataSource delegate;
        /// Set by the test thread, read by the persistence thread each time it borrows a connection.
        private final AtomicBoolean failConnections = new AtomicBoolean();
        /// Set by the test thread, read by the persistence thread as it prepares each statement.
        private final AtomicReference<String> failStatementsContaining = new AtomicReference<>();
        /// Set by the test thread, read by the persistence thread at each commit.
        private final AtomicBoolean commitsTakeEffectButReportFailure = new AtomicBoolean();
        /// Set by the test thread, read by the persistence thread at a commit that reports failure, to fail every connection borrowed after it.
        private final AtomicBoolean unreachableOnceACommitReportsFailure = new AtomicBoolean();

        private ToggleableDataSource(DataSource delegate) {
            this.delegate = checkNotNull(delegate, "delegate");
        }

        private void setFailConnections(boolean failConnections) {
            this.failConnections.set(failConnections);
        }

        /// Fails one statement on an otherwise working connection, which reaches a failure in the middle of a transaction that failing the connection outright
        /// cannot.
        ///
        /// @param sqlFragment fails every statement whose SQL contains it, or `null` to stop failing statements
        private void setFailStatementsContaining(@Nullable String sqlFragment) {
            failStatementsContaining.set(sqlFragment);
        }

        /// Makes each commit take effect and then throw, as a commit does when the connection drops after the server has committed.
        private void setCommitsTakeEffectButReportFailure(boolean commitsTakeEffectButReportFailure) {
            this.commitsTakeEffectButReportFailure.set(commitsTakeEffectButReportFailure);
        }

        /// Fails every connection from the moment a commit reports its failure, so nothing can read back what that commit left.
        private void setUnreachableOnceACommitReportsFailure(boolean unreachableOnceACommitReportsFailure) {
            this.unreachableOnceACommitReportsFailure.set(unreachableOnceACommitReportsFailure);
        }

        // The connection is handed to the caller, which closes it.
        @SuppressWarnings("JDBCResourceOpenedButNotSafelyClosed")
        @Override
        public Connection getConnection() throws SQLException {
            if (failConnections.get()) {
                throw new SQLException("Simulated connection failure");
            }
            Connection connection = delegate.getConnection();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                                                       new Class<?>[]{Connection.class},
                                                       (_, method, args) -> {
                                                           String sqlFragment = failStatementsContaining.get();
                                                           if (sqlFragment != null
                                                               && "prepareStatement".equals(method.getName())
                                                               && ((String) args[0]).contains(sqlFragment)) {
                                                               throw new SQLException(STATEMENT_FAILURE);
                                                           }
                                                           Object result;
                                                           try {
                                                               result = method.invoke(connection, args);
                                                           } catch (InvocationTargetException e) {
                                                               throw e.getCause();
                                                           }
                                                           if ("commit".equals(method.getName()) && commitsTakeEffectButReportFailure.get()) {
                                                               failConnections.set(unreachableOnceACommitReportsFailure.get());
                                                               throw new SQLException(EFFECTIVE_COMMIT_FAILURE);
                                                           }
                                                           return result;
                                                       });
        }

        @Override
        public void close() {
        }
    }

    private interface RowReader<T> {
        T read(ResultSet resultSet) throws SQLException;
    }

    /// A column as `information_schema.columns` describes it.
    ///
    /// @param table        the name of the column's table, without the domain's prefix
    /// @param column       the column's name
    /// @param dataType     the column's type, as `information_schema.columns.data_type` names it
    /// @param nullable     whether the column accepts `null`
    /// @param defaultValue the column's default expression; `null` when the column has no default
    private record ColumnDescription(String table, String column, String dataType, boolean nullable, @Nullable String defaultValue) {}

    /// A primary or foreign key as `information_schema` describes it.
    ///
    /// @param table      the name of the key's table, without the domain's prefix
    /// @param type       `PRIMARY KEY` or `FOREIGN KEY`
    /// @param columns    the key's columns, in key order
    /// @param references the table and column that a foreign key references, as `table(column)` without the domain's prefix; `null` for a primary key
    private record KeyDescription(String table, String type, List<String> columns, @Nullable String references) {}
}

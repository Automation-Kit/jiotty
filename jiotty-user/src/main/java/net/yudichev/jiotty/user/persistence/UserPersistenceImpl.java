package net.yudichev.jiotty.user.persistence;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Iterables;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.async.TaskFailureReporter;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.SerialisedListeners;
import net.yudichev.jiotty.common.misc.UniqueId;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import net.yudichev.jiotty.persistence.db.CloseableDataSource;
import net.yudichev.jiotty.persistence.db.DataSourceFactory;
import net.yudichev.jiotty.persistence.domain.PersistenceDomain;
import net.yudichev.jiotty.persistence.domain.PersistenceDomainConfig;
import net.yudichev.jiotty.persistence.domain.PersistenceDomainMigrator;
import net.yudichev.jiotty.persistence.domain.PersistenceDomainService;
import net.yudichev.jiotty.user.persistence.UserStatePublisher.IdentitySubscription;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.persistence.domain.PersistenceDomainConfig.DOMAIN_PREFIX_PLACEHOLDER;
import static net.yudichev.jiotty.user.persistence.UserPersistenceModule.Dependency;
import static net.yudichev.jiotty.user.persistence.UserPersistenceModule.DomainName;
import static net.yudichev.jiotty.user.persistence.UserPersistenceModule.Executor;
import static net.yudichev.jiotty.user.persistence.UserPersistenceModule.InitStatements;
import static net.yudichev.jiotty.user.persistence.UserPersistenceModule.Migrator;
import static net.yudichev.jiotty.user.persistence.UserPersistenceModule.SchemaVersion;

@SuppressWarnings("JDBCPrepareStatementWithNonConstantString")
public class UserPersistenceImpl extends BaseLifecycleComponent implements UserPersistence {
    @VisibleForTesting
    static final String USER_TABLE = "user";
    @VisibleForTesting
    static final String IDENTITY_TABLE = "identity";
    @VisibleForTesting
    static final String ACTIVITY_DAY_TABLE = "user_activity_day";
    @VisibleForTesting
    static final String ATTACHMENT_TABLE = "user_attachment";
    static final String USER_TABLE_TEMPLATE = DOMAIN_PREFIX_PLACEHOLDER + USER_TABLE;
    @VisibleForTesting
    static final String IDENTITY_TABLE_TEMPLATE = DOMAIN_PREFIX_PLACEHOLDER + IDENTITY_TABLE;
    @VisibleForTesting
    static final String ACTIVITY_DAY_TABLE_TEMPLATE = DOMAIN_PREFIX_PLACEHOLDER + ACTIVITY_DAY_TABLE;
    @VisibleForTesting
    static final String ATTACHMENT_TABLE_TEMPLATE = DOMAIN_PREFIX_PLACEHOLDER + ATTACHMENT_TABLE;
    private static final String USER_ID_FOREIGN_KEY_DDL = "FOREIGN KEY (user_id) REFERENCES " + USER_TABLE_TEMPLATE + "(id)";
    /// Shared with [UserSchemaMigrator], which adds the column to a store created before it existed: one statement, so a fresh store and a migrated one
    /// cannot end up with differently-shaped indexes. The `deleted_at IS NULL` matches what [#listInactiveSince] scans.
    static final String LAST_ACTIVE_AT_INDEX_DDL =
            "CREATE INDEX IF NOT EXISTS " + DOMAIN_PREFIX_PLACEHOLDER + "user_last_active_at_idx ON " + USER_TABLE_TEMPLATE +
            " (last_active_at) WHERE deleted_at IS NULL;";
    @VisibleForTesting
    static final String ACTIVITY_HISTORY_DISABLED_COLUMN = "activity_history_disabled";
    static final String ACTIVITY_HISTORY_DISABLED_COLUMN_DDL = ACTIVITY_HISTORY_DISABLED_COLUMN + " boolean NOT NULL DEFAULT false";
    /// Creates the {@value #ACTIVITY_DAY_TABLE} table. The table holds one row for each user, kind and UTC day that [#recordActivity] recorded while the
    /// user's activity history was enabled.
    static final String ACTIVITY_DAY_TABLE_DDL = "CREATE TABLE IF NOT EXISTS " + ACTIVITY_DAY_TABLE_TEMPLATE +
                                                 """
                                                 (
                                                     user_id text NOT NULL,
                                                     day date NOT NULL,
                                                     kind text NOT NULL,
                                                     PRIMARY KEY (user_id, day, kind),
                                                 """ + USER_ID_FOREIGN_KEY_DDL + ");";
    static final String ATTACHMENT_TABLE_DDL = "CREATE TABLE IF NOT EXISTS " + ATTACHMENT_TABLE_TEMPLATE +
                                               """
                                               (
                                                   user_id text NOT NULL,
                                                   name text NOT NULL,
                                                   value jsonb NOT NULL,
                                                   PRIMARY KEY (user_id, name),
                                               """ + USER_ID_FOREIGN_KEY_DDL + ");";
    @VisibleForTesting
    static final String FAILED_COMMIT_READ_BACK_TASK = "reading back a user whose commit reported a failure";
    private static final Logger logger = LogManager.getLogger(UserPersistenceImpl.class);
    private static final String UNIQUE_VIOLATION_SQL_STATE = "23505";
    private static final List<String> BASE_INIT_STATEMENTS = ImmutableList.of(
            "CREATE TABLE IF NOT EXISTS " + USER_TABLE_TEMPLATE +
            """
             (
                id text PRIMARY KEY,
                email text NOT NULL UNIQUE,
                display_name text,
                timezone text NOT NULL,
                created_at timestamptz NOT NULL,
                updated_at timestamptz NOT NULL,
                last_active_at timestamptz NOT NULL,
                deleted_at timestamptz,
            """ + ACTIVITY_HISTORY_DISABLED_COLUMN_DDL + ");",
            LAST_ACTIVE_AT_INDEX_DDL,
            ACTIVITY_DAY_TABLE_DDL,
            ATTACHMENT_TABLE_DDL,
            "CREATE TABLE IF NOT EXISTS " + IDENTITY_TABLE_TEMPLATE +
            """
             (
                user_id text NOT NULL,
                provider text NOT NULL,
                provider_user_id text NOT NULL,
                created_at timestamptz NOT NULL,
                updated_at timestamptz NOT NULL,
                deleted_at timestamptz,
                PRIMARY KEY (user_id, provider),
                UNIQUE (provider, provider_user_id),
            """ + USER_ID_FOREIGN_KEY_DDL + ");",
            "CREATE INDEX IF NOT EXISTS " + DOMAIN_PREFIX_PLACEHOLDER + "identity_user_id_idx ON " + IDENTITY_TABLE_TEMPLATE + " (user_id);"
    );

    private final DataSourceFactory dataSourceFactory;
    private final Provider<SchedulingExecutor> executorProvider;
    private final PersistenceDomainService persistenceDomainService;
    private final CurrentDateTimeProvider timeProvider;
    private final PersistenceDomainConfig domainConfig;
    private final String selectUserByIdentitySql;
    private final String resolveUserByIdentitySql;
    private final String selectUserByIdSql;
    private final String selectUserByIdIgnoringDeletionSql;
    private final String selectAllUsersSql;
    private final String selectAllUsersIgnoringDeletionSql;
    private final String selectUserByIdWithDeletionSql;
    private final String selectActivityHistoryDisabledSql;
    private final String selectIdentityByUserAndProviderSql;
    private final String selectIdentityByProviderUserIdSql;
    private final String selectActiveIdentityProvidersByUserSql;
    private final String insertUserSql;
    private final String insertIdentitySql;
    private final String updateIdentitySql;
    private final String updateIdentityDeletedAtSql;
    private final String listIdentitiesSql;
    private final String listIdentitiesIgnoringDeletionSql;
    private final String softDeleteUserSql;
    private final String softDeleteIdentitiesSql;
    private final String hardDeleteUserSql;
    private final String hardDeleteIdentitiesSql;
    private final String selectUserDeletedAtSql;
    private final String restoreUserSql;
    private final String restoreIdentitiesSql;
    private final String updateUserSql;
    private final String touchLastActiveSql;
    private final String selectInactiveSinceSql;
    private final String softDeleteUserIfInactiveSql;
    private final String insertActivityDaySql;
    private final String updateActivityHistoryDisabledSql;
    private final String deleteActivityDaysByUserSql;
    private final String deleteActivityDaysBeforeSql;
    private final String upsertAttachmentSql;
    private final String deleteAttachmentsByUserSql;
    private final String selectActivityDaysByUserSql;
    private final String selectAttachmentsByUserSql;
    private final String selectAllActivityHistoryDisabledSql;
    private final String selectAllActivityDaysSinceSql;
    private final String selectAllAttachmentsSql;

    private final UserStatePublisher publisher;
    private final TaskFailureReporter taskFailureReporter;

    private SchedulingExecutor executor;
    private CloseableDataSource dataSource;

    @Inject
    public UserPersistenceImpl(@Dependency DataSourceFactory dataSourceFactory,
                               @Executor Provider<SchedulingExecutor> executorProvider,
                               PersistenceDomainService persistenceDomainService,
                               CurrentDateTimeProvider timeProvider,
                               @SchemaVersion int schemaVersion,
                               @DomainName String domainName,
                               @InitStatements List<String> initStatements,
                               @Migrator PersistenceDomainMigrator migrator,
                               TaskFailureReporter taskFailureReporter) {
        this.taskFailureReporter = checkNotNull(taskFailureReporter, "taskFailureReporter");
        publisher = new UserStatePublisher(command -> executor.tryExecute("unsubscribe from user changes", command));
        this.dataSourceFactory = checkNotNull(dataSourceFactory, "dataSourceFactory");
        this.executorProvider = checkNotNull(executorProvider, "executorProvider");
        this.persistenceDomainService = checkNotNull(persistenceDomainService, "persistenceDomainService");
        this.timeProvider = checkNotNull(timeProvider, "timeProvider");
        checkArgument(schemaVersion > 0, "schemaVersion must be > 0, was %s", schemaVersion);
        var domain = new PersistenceDomain(checkNotNull(domainName, "domainName"));
        String userTable = domain.prefix() + USER_TABLE;
        String identityTable = domain.prefix() + IDENTITY_TABLE;
        domainConfig = new PersistenceDomainConfig(domain,
                                                   schemaVersion,
                                                   mergeInitStatements(BASE_INIT_STATEMENTS, checkNotNull(initStatements, "initStatements")),
                                                   checkNotNull(migrator, "migrator"));
        selectUserByIdentitySql = "SELECT u.id, u.email, u.display_name, u.timezone, u.created_at, u.updated_at " +
                                  "FROM " + userTable + " u JOIN " + identityTable + " i ON u.id = i.user_id " +
                                  "WHERE i.provider=? AND i.provider_user_id=? AND i.deleted_at IS NULL AND u.deleted_at IS NULL";
        // Single-query resolution for the recovery / pending-deletion flow: join identity→user WITHOUT the deleted_at filter and return u.deleted_at so the
        // caller can tell Active from SoftDeleted. The identity rows are soft-deleted together with the user (softDeleteIdentitiesSql), so do NOT filter on
        // i.deleted_at — match on the still-unique (provider, provider_user_id).
        resolveUserByIdentitySql = "SELECT u.id, u.email, u.display_name, u.timezone, u.created_at, u.updated_at, u.deleted_at " +
                                   "FROM " + userTable + " u JOIN " + identityTable + " i ON u.id = i.user_id " +
                                   "WHERE i.provider=? AND i.provider_user_id=?";
        selectUserByIdSql = "SELECT id, email, display_name, timezone, created_at, updated_at FROM " + userTable +
                            " WHERE id=? AND deleted_at IS NULL";
        selectUserByIdIgnoringDeletionSql = "SELECT id, email, display_name, timezone, created_at, updated_at FROM " + userTable + " WHERE id=?";
        selectAllUsersSql = "SELECT id, email, display_name, timezone, created_at, updated_at FROM " + userTable +
                            " WHERE deleted_at IS NULL";
        selectAllUsersIgnoringDeletionSql = "SELECT id, email, display_name, timezone, created_at, updated_at, deleted_at FROM " + userTable;
        selectUserByIdWithDeletionSql = selectAllUsersIgnoringDeletionSql + " WHERE id=?";
        selectActivityHistoryDisabledSql = "SELECT " + ACTIVITY_HISTORY_DISABLED_COLUMN + " FROM " + userTable + " WHERE id=?";
        selectIdentityByUserAndProviderSql =
                "SELECT provider_user_id, deleted_at FROM " + identityTable + " WHERE user_id=? AND provider=?";
        selectIdentityByProviderUserIdSql =
                "SELECT user_id FROM " + identityTable + " WHERE provider=? AND provider_user_id=?";
        selectActiveIdentityProvidersByUserSql =
                "SELECT provider FROM " + identityTable + " WHERE user_id=? AND deleted_at IS NULL";
        insertUserSql =
                "INSERT INTO " + userTable + " (id, email, display_name, timezone, created_at, updated_at, last_active_at) VALUES (?,?,?,?,?,?,?)";
        insertIdentitySql =
                "INSERT INTO " + identityTable + " (user_id, provider, provider_user_id, created_at, updated_at) VALUES (?,?,?,?,?)";
        updateIdentitySql =
                "UPDATE " + identityTable + " SET provider_user_id=?, deleted_at=?, updated_at=? WHERE user_id=? AND provider=?";
        updateIdentityDeletedAtSql =
                "UPDATE " + identityTable + " SET deleted_at=?, updated_at=? WHERE user_id=? AND provider=?";
        listIdentitiesSql =
                "SELECT i.provider, i.provider_user_id, i.created_at, i.updated_at FROM " + identityTable + " i " +
                "JOIN " + userTable + " u ON u.id = i.user_id " +
                "WHERE i.user_id=? AND i.deleted_at IS NULL AND u.deleted_at IS NULL";
        listIdentitiesIgnoringDeletionSql =
                "SELECT provider, provider_user_id, created_at, updated_at FROM " + identityTable + " WHERE user_id=?";
        softDeleteUserSql = "UPDATE " + userTable + " SET deleted_at=?, updated_at=? WHERE id=? AND deleted_at IS NULL";
        softDeleteIdentitiesSql =
                "UPDATE " + identityTable + " SET deleted_at=?, updated_at=? WHERE user_id=? AND deleted_at IS NULL";
        hardDeleteIdentitiesSql = "DELETE FROM " + identityTable + " WHERE user_id=?";
        hardDeleteUserSql = "DELETE FROM " + userTable + " WHERE id=?";
        selectUserDeletedAtSql = "SELECT deleted_at FROM " + userTable + " WHERE id=?";
        restoreUserSql = "UPDATE " + userTable + " SET deleted_at=NULL, updated_at=? WHERE id=? AND deleted_at IS NOT NULL";
        restoreIdentitiesSql = "UPDATE " + identityTable + " SET deleted_at=NULL, updated_at=? WHERE user_id=? AND deleted_at=?";
        updateUserSql = "UPDATE " + userTable + " SET email=?, display_name=?, timezone=?, updated_at=? WHERE id=? AND deleted_at IS NULL";
        // Monotonic by construction: the last_active_at predicate makes an out-of-order or repeated call a no-op rather than a backwards move. Matches on the
        // id alone, so a soft-deleted user that is later restored comes back carrying what was recorded while it was deleted.
        touchLastActiveSql = "UPDATE " + userTable + " SET last_active_at=? WHERE id=? AND last_active_at<?";
        // Ordered by the column being selected on, which UserProfile does not carry: without it here the ordering could not be reproduced from the result.
        // Limited because a cutoff that matches everybody — a clock or policy error — would otherwise materialise the whole table.
        selectInactiveSinceSql = "SELECT id, email, display_name, timezone, created_at, updated_at FROM " + userTable +
                                 " WHERE deleted_at IS NULL AND last_active_at<? ORDER BY last_active_at LIMIT ?";
        softDeleteUserIfInactiveSql =
                "UPDATE " + userTable + " SET deleted_at=?, updated_at=? WHERE id=? AND deleted_at IS NULL AND last_active_at<?";
        String activityDayTable = domain.prefix() + ACTIVITY_DAY_TABLE;
        String attachmentTable = domain.prefix() + ATTACHMENT_TABLE;
        insertActivityDaySql = "INSERT INTO " + activityDayTable + " (user_id, day, kind) VALUES (?,?,?) ON CONFLICT DO NOTHING";
        updateActivityHistoryDisabledSql = "UPDATE " + userTable + " SET " + ACTIVITY_HISTORY_DISABLED_COLUMN + "=? WHERE id=?";
        deleteActivityDaysByUserSql = "DELETE FROM " + activityDayTable + " WHERE user_id=?";
        deleteActivityDaysBeforeSql = "DELETE FROM " + activityDayTable + " WHERE day<?";
        // The statement writes no row when the stored `value` equals the new one. Its row count is therefore 1 when the attachment is inserted or changed, and
        // 0 when the attachment is unchanged.
        upsertAttachmentSql = "INSERT INTO " + attachmentTable +
                              """
                               AS stored (user_id, name, value) VALUES (?,?,?::jsonb)
                              ON CONFLICT (user_id, name) DO UPDATE SET value=EXCLUDED.value WHERE stored.value IS DISTINCT FROM EXCLUDED.value""";
        deleteAttachmentsByUserSql = "DELETE FROM " + attachmentTable + " WHERE user_id=?";
        selectActivityDaysByUserSql = "SELECT kind, day FROM " + activityDayTable + " WHERE user_id=?";
        selectAttachmentsByUserSql = "SELECT name, value FROM " + attachmentTable + " WHERE user_id=?";
        selectAllActivityHistoryDisabledSql = "SELECT id, " + ACTIVITY_HISTORY_DISABLED_COLUMN + " FROM " + userTable;
        selectAllActivityDaysSinceSql = "SELECT user_id, kind, day FROM " + activityDayTable + " WHERE day>=?";
        selectAllAttachmentsSql = "SELECT user_id, name, value FROM " + attachmentTable;
    }

    @Override
    protected void doStart() {
        executor = executorProvider.get();
        asUnchecked(() -> persistenceDomainService.ensureDomainReady(domainConfig).get(30, TimeUnit.SECONDS));
        dataSource = dataSourceFactory.create("user-persistence");
    }

    @Override
    protected void doStop() {
        Closeable.closeSafelyIfNotNull(logger, dataSource);
    }

    @Override
    public CompletableFuture<UserCreationResult> getOrCreateByIdentity(UserIdentity identity, UserProfileInput profile) {
        checkNotNull(identity, "identity");
        checkNotNull(profile, "profile");
        return whenStartedAndNotLifecycling(() -> executor.submitOrFail(() -> doGetOrCreateByIdentity(identity, profile)));
    }

    @Override
    public CompletableFuture<Optional<UserProfile>> getByIdentity(UserIdentity identity) {
        checkNotNull(identity, "identity");
        return whenStartedAndNotLifecycling(() -> executor.submitOrFail(() -> Optional.ofNullable(doGetByIdentity(identity))));
    }

    @Override
    public CompletableFuture<IdentityResolution> resolveByIdentity(UserIdentity identity) {
        checkNotNull(identity, "identity");
        return whenStartedAndNotLifecycling(() -> executor.submitOrFail(() -> doResolveByIdentity(identity)));
    }

    @Override
    public CompletableFuture<Optional<UserProfile>> getById(String userId) {
        validateUserId(userId);
        return whenStartedAndNotLifecycling(() -> executor.submitOrFail(() -> Optional.ofNullable(doGetById(userId))));
    }

    /// Never completes once this component has stopped, rather than throwing at the caller: a long-running caller still reading through here while the process
    /// drains would otherwise see a clean shutdown as its own failure.
    @Override
    public CompletableFuture<Optional<UserProfile>> getByIdIgnoringDeletion(String userId) {
        validateUserId(userId);
        return whenNotLifecycling(() -> isStartedPlain()
                                        ? executor.submitOrFail(() -> Optional.ofNullable(doGetByIdIgnoringDeletion(userId)))
                                        : new CompletableFuture<>());
    }

    @Override
    public CompletableFuture<List<UserProfile>> listAllProfiles() {
        return whenStartedAndNotLifecycling(() -> executor.submitOrFail(this::doListAllProfiles));
    }

    @Override
    public CompletableFuture<List<UserProfileWithDeletion>> listAllProfilesIgnoringDeletion() {
        return whenStartedAndNotLifecycling(() -> executor.submitOrFail(this::doListAllProfilesIgnoringDeletion));
    }

    @Override
    public CompletableFuture<Boolean> existsIgnoringDeletion(String userId) {
        validateUserId(userId);
        return whenStartedAndNotLifecycling(() -> executor.submitOrFail(() -> doExistsIgnoringDeletion(userId)));
    }

    @Override
    public CompletableFuture<Boolean> touchLastActive(String userId, Instant activeAt) {
        validateUserId(userId);
        checkNotNull(activeAt, "activeAt");
        return whenStartedAndNotLifecycling(() -> executor.submitOrFail(
                () -> withConnection("Failed to record activity for user ", userId, connection -> touchLastActive(connection, userId, activeAt))));
    }

    @Override
    public CompletableFuture<ActivityRecordOutcome> recordActivity(String userId, String kind, LocalDate day) {
        validateUserId(userId);
        checkNotNull(kind, "kind");
        checkNotNull(day, "day");
        return whenStartedAndNotLifecycling(() -> executor.submitOrFail(() -> doRecordActivity(userId, kind, day)));
    }

    @Override
    public CompletableFuture<Boolean> setActivityHistoryDisabled(String userId, boolean disabled) {
        validateUserId(userId);
        return whenStartedAndNotLifecycling(() -> executor.submitOrFail(() -> doSetActivityHistoryDisabled(userId, disabled)));
    }

    @Override
    public CompletableFuture<Boolean> putAttachment(String userId, String name, String json) {
        validateUserId(userId);
        checkNotNull(name, "name");
        UserPersistence.checkAttachmentLength(name, json);
        return whenStartedAndNotLifecycling(() -> executor.submitOrFail(() -> doPutAttachment(userId, name, json)));
    }

    @Override
    public CompletableFuture<Integer> purgeActivityDaysBefore(LocalDate day) {
        checkNotNull(day, "day");
        return whenStartedAndNotLifecycling(() -> executor.submitOrFail(() -> doPurgeActivityDaysBefore(day)));
    }

    @Override
    public CompletableFuture<Optional<UserRecord>> readRecord(String userId) {
        validateUserId(userId);
        return whenStartedAndNotLifecycling(() -> executor.submitOrFail(() -> Optional.ofNullable(doReadRecord(userId))));
    }

    @Override
    public CompletableFuture<List<UserProfile>> listInactiveSince(Instant cutoff, int limit) {
        checkNotNull(cutoff, "cutoff");
        checkArgument(limit > 0, "limit must be positive, got %s", limit);
        return whenStartedAndNotLifecycling(() -> executor.submitOrFail(() -> doListInactiveSince(cutoff, limit)));
    }

    @Override
    public CompletableFuture<ConditionalSoftDeleteOutcome> softDeleteIfInactiveSince(String userId, Instant cutoff) {
        validateUserId(userId);
        checkNotNull(cutoff, "cutoff");
        // When no user is soft-deleted, the outcome says which of the three reasons applies.
        return whenStartedAndNotLifecycling(() -> executor.submitOrFail(() -> softDeleteInTransaction(userId, cutoff)));
    }

    @Override
    public CompletableFuture<UserProfile> updateProfile(String userId, UserProfileInput profile) {
        validateUserId(userId);
        checkNotNull(profile, "profile");
        return whenStartedAndNotLifecycling(() -> executor.submitOrFail(() -> doUpdateProfile(userId, profile)));
    }

    @Override
    public CompletableFuture<Void> updateAllIdentities(String userId, List<UserIdentity> identities) {
        validateUserId(userId);
        var identitiesCopy = ImmutableList.copyOf(checkNotNull(identities, "identities"));
        validateDistinctIdentityProviders(identitiesCopy);
        return whenStartedAndNotLifecycling(() -> executor.submitOrFail(() -> {
            doUpdateAllIdentities(userId, identitiesCopy);
            return null;
        }));
    }

    @Override
    public CompletableFuture<List<UserIdentityRecord>> listIdentities(String userId) {
        validateUserId(userId);
        return whenStartedAndNotLifecycling(() -> executor.submitOrFail(() -> doListIdentities(listIdentitiesSql, userId)));
    }

    @Override
    public CompletableFuture<List<UserIdentityRecord>> listIdentitiesIgnoringDeletion(String userId) {
        validateUserId(userId);
        return whenStartedAndNotLifecycling(() -> executor.submitOrFail(() -> doListIdentities(listIdentitiesIgnoringDeletionSql, userId)));
    }

    @Override
    public CompletableFuture<Void> softDelete(String userId) {
        validateUserId(userId);
        return whenStartedAndNotLifecycling(() -> executor.submitOrFail(() -> {
            // A repeated call for a soft-deleted user changes nothing and succeeds, as a repeated `restore` or `hardDelete` does, because the update only
            // matches a user who is not soft-deleted.
            softDeleteInTransaction(userId, null);
            return null;
        }));
    }

    @Override
    public CompletableFuture<Void> hardDelete(String userId) {
        validateUserId(userId);
        return whenStartedAndNotLifecycling(() -> executor.submitOrFail(() -> doHardDelete(userId)).thenCompose(removal -> removal));
    }

    @Override
    public CompletableFuture<Void> restore(String userId) {
        validateUserId(userId);
        return whenStartedAndNotLifecycling(() -> executor.submitOrFail(() -> {
            doRestore(userId);
            return null;
        }));
    }

    @Override
    public Closeable subscribe(UserStateListener listener) {
        return whenStartedAndNotLifecycling(() -> {
            SerialisedListeners<UserStateListener>.Subscription subscription = publisher.newSubscription(listener);
            activateOnExecutor("subscribe to user states",
                               this::doListAllProfilesIgnoringDeletion,
                               image -> publisher.activate(subscription, image),
                               listener::onSubscriptionFailed);
            return subscription;
        });
    }

    @Override
    public Closeable subscribe(UserIdentity identity, IdentityResolutionListener listener) {
        return whenStartedAndNotLifecycling(() -> {
            IdentitySubscription subscription = publisher.newSubscription(identity, listener);
            activateOnExecutor("subscribe to an identity",
                               () -> doResolveByIdentity(identity),
                               resolution -> publisher.activate(subscription, resolution),
                               listener::onSubscriptionFailed);
            return subscription;
        });
    }

    @Override
    public Closeable subscribe(LocalDate earliestDay, UserRecordListener listener) {
        checkNotNull(earliestDay, "earliestDay");
        return whenStartedAndNotLifecycling(() -> {
            SerialisedListeners<UserRecordListener>.Subscription subscription = publisher.newSubscription(earliestDay, listener);
            activateOnExecutor("subscribe to user records",
                               () -> doListRecords(earliestDay),
                               image -> publisher.activateRecords(subscription, image),
                               listener::onSubscriptionFailed);
            return subscription;
        });
    }

    /// Reads a subscription's first delivery and activates the subscription with it in one task, so no write can fall between the two.
    ///
    /// @param failureHandler told when the read fails, which activates nothing
    private <T> void activateOnExecutor(String taskName,
                                        Supplier<? extends T> firstDeliveryReader,
                                        Consumer<? super T> activation,
                                        Consumer<Throwable> failureHandler) {
        executor.execute(taskName, () -> {
            T firstDelivery;
            try {
                firstDelivery = firstDeliveryReader.get();
            } catch (RuntimeException e) {
                failureHandler.accept(e);
                return;
            }
            activation.accept(firstDelivery);
        });
    }

    private UserCreationResult doGetOrCreateByIdentity(UserIdentity identity, UserProfileInput profile) {
        try {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    UserProfile existing = selectUserByIdentity(connection, identity);
                    if (existing != null) {
                        connection.commit();
                        return new UserCreationResult.Resolved(existing, false);
                    }
                    // The window a competing transaction has to claim this identity. `assert` so production, which runs
                    // without -ea, never even calls it; surefire enables assertions, so the race test can block here.
                    assert onBeforeInsertingNewUser();
                    Instant now = persistenceNow();
                    String userId = UniqueId.generate('u');
                    insertUser(connection, userId, profile, now);
                    insertIdentity(connection, userId, identity, now);
                    var createdProfile = new UserProfile(userId, profile.email(), profile.displayName(), profile.timezone(), now, now);
                    List<UserIdentity> linkedIdentities = ImmutableList.of(identity);
                    commit(connection, userId, linkedIdentities);
                    publisher.publishChanged(new UserProfileWithDeletion(createdProfile, Optional.empty()), linkedIdentities, ImmutableList.of());
                    return new UserCreationResult.Resolved(createdProfile, true);
                } catch (SQLException e) {
                    rollbackQuietly(connection);
                    if (!isUniqueViolation(e)) {
                        throw new RuntimeException("Failed creating user for identity " + identity, e);
                    }
                } finally {
                    endTransaction(connection);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed creating user for identity " + identity, e);
        }

        // Either unique constraint can land here, so the re-read decides which one fired. It resolves rather than selects,
        // because both constraints span soft-deleted rows while the plain select hides them: a soft-deleted user holding
        // this identity would otherwise look like an absent identity and be misreported as an email conflict.
        return switch (doResolveByIdentity(identity)) {
            // A concurrent call created the very user we wanted between our select and our insert, so both callers get it.
            case IdentityResolution.Active(UserProfile concurrentlyCreated) -> new UserCreationResult.Resolved(concurrentlyCreated, false);
            // The identity itself is taken by a user that is soft-deleted, so it is unavailable and no email conflict
            // arose. Callers distinguish this state up front with resolveByIdentity; reaching it here means that step was
            // skipped, which no correct caller does.
            case IdentityResolution.SoftDeleted _ ->
                    throw new IllegalStateException("Identity " + identity + " belongs to a soft-deleted user; resolve it before creating");
            // The identity is genuinely free, so the violation was the email — it belongs to a different user.
            case IdentityResolution.Absent _ -> {
                logger.info("[{}] Refusing to create a user: its email already belongs to another user", identity);
                yield UserCreationResult.EmailAlreadyInUse.INSTANCE;
            }
        };
    }

    /// Test seam: called inside the create transaction, after the identity lookup found nothing and before the rows are written — the window in which a
    /// competing transaction can claim the same identity. Returns `true` so the call site can be an `assert`, which keeps production (no `-ea`) out of it.
    @VisibleForTesting
    @SuppressWarnings("SameReturnValue")
    boolean onBeforeInsertingNewUser() {
        return true;
    }

    private @Nullable UserProfile doGetByIdentity(UserIdentity identity) {
        return withConnection("Failed to read user for identity ", identity, connection -> selectUserByIdentity(connection, identity));
    }

    private IdentityResolution doResolveByIdentity(UserIdentity identity) {
        return withConnection("Failed to resolve user for identity ", identity, connection -> resolveUserByIdentity(connection, identity));
    }

    private @Nullable UserProfile doGetById(String userId) {
        return withConnection("Failed to read user ", userId, connection -> selectUserById(connection, userId));
    }

    /// @return `null` when no row exists for `userId`; a soft-deleted user has a row
    private @Nullable UserProfile doGetByIdIgnoringDeletion(String userId) {
        return withConnection("Failed to read user ignoring deletion ", userId, connection -> selectUserByIdIgnoringDeletion(connection, userId));
    }

    private List<UserProfile> doListAllProfiles() {
        return queryList(selectAllUsersSql, _ -> {}, UserPersistenceImpl::mapUserProfile, "Failed to list user profiles");
    }

    private List<UserProfileWithDeletion> doListAllProfilesIgnoringDeletion() {
        return queryList(selectAllUsersIgnoringDeletionSql,
                         _ -> {},
                         UserPersistenceImpl::mapUserProfileWithDeletion,
                         "Failed to list user profiles ignoring deletion");
    }

    private boolean doExistsIgnoringDeletion(String userId) {
        return withConnection("Failed to check existence of user ", userId, connection -> selectActivityHistoryDisabled(connection, userId) != null);
    }

    private UserProfile doUpdateProfile(String userId, UserProfileInput profile) {
        return inTransaction("Failed to update user ", userId, connection -> {
            Instant now = persistenceNow();
            doUpdate(connection, updateUserSql, stmt -> {
                stmt.setString(1, profile.email());
                stmt.setString(2, profile.displayName().orElse(null));
                stmt.setString(3, profile.timezone().getId());
                stmt.setTimestamp(4, Timestamp.from(now));
                stmt.setString(5, userId);
            });
            UserProfile updatedProfile = selectUserById(connection, userId);
            assert updatedProfile != null : "the update matched this user's live row, which this transaction keeps locked until it commits";
            commit(connection, userId, ImmutableList.of());
            publisher.publishChanged(new UserProfileWithDeletion(updatedProfile, Optional.empty()), ImmutableList.of(), ImmutableList.of());
            return updatedProfile;
        });
    }

    private void doUpdateAllIdentities(String userId, List<UserIdentity> identities) {
        inTransaction("Failed to update identities of user ", userId, connection -> {
            UserProfileWithDeletion user = selectUserByIdWithDeletion(connection, userId);
            checkState(user != null && user.deletedAt().isEmpty(), "User %s not found or deleted", userId);
            Instant now = persistenceNow();
            boolean anyIdentityWritten = false;
            Set<String> providers = HashSet.newHashSet(identities.size());
            var linkedIdentitiesBuilder = ImmutableList.<UserIdentity>builderWithExpectedSize(identities.size());
            var unlinkedIdentitiesBuilder = ImmutableList.<UserIdentity>builderWithExpectedSize(identities.size());
            for (UserIdentity identity : identities) {
                providers.add(identity.provider());
                String existingUserId = selectIdentityByProviderUserId(connection, identity);
                checkState(existingUserId == null || existingUserId.equals(userId), "%s already linked to another user", identity);
                IdentityLinkRecord identityByProvider = selectIdentityByUserAndProvider(connection, userId, identity.provider());
                if (identityByProvider == null) {
                    insertIdentity(connection, userId, identity, now);
                    linkedIdentitiesBuilder.add(identity);
                    anyIdentityWritten = true;
                } else {
                    boolean repointed = !identityByProvider.providerUserId().equals(identity.providerUserId());
                    if (repointed || !identityByProvider.active()) {
                        updateIdentity(connection, userId, identity, now);
                        anyIdentityWritten = true;
                        if (repointed) {
                            unlinkedIdentitiesBuilder.add(new UserIdentity(identity.provider(), identityByProvider.providerUserId()));
                            linkedIdentitiesBuilder.add(identity);
                        }
                    }
                }
            }
            // The loop below sets `deleted_at` on the identity row of each provider missing from `identities`. `resolveUserByIdentitySql` reads identity rows
            // whatever their `deleted_at`, so such a row continues to resolve to this user.
            for (String existingProvider : selectActiveIdentityProvidersByUser(connection, userId)) {
                if (!providers.contains(existingProvider)) {
                    updateIdentityDeletedAt(connection, userId, existingProvider, now, now);
                    anyIdentityWritten = true;
                }
            }
            List<UserIdentity> linkedIdentities = linkedIdentitiesBuilder.build();
            List<UserIdentity> unlinkedIdentities = unlinkedIdentitiesBuilder.build();
            commit(connection, userId, Iterables.concat(linkedIdentities, unlinkedIdentities));
            if (anyIdentityWritten) {
                publisher.publishChanged(user, linkedIdentities, unlinkedIdentities);
            }
            return null;
        });
    }

    private ActivityRecordOutcome doRecordActivity(String userId, String kind, LocalDate day) {
        return inTransaction("Failed to record an activity day for user ", userId, connection -> {
            Boolean historyDisabled = selectActivityHistoryDisabled(connection, userId);
            if (historyDisabled == null) {
                return ActivityRecordOutcome.ABSENT;
            }
            touchLastActive(connection, userId, day.atStartOfDay(ZoneOffset.UTC).toInstant());
            boolean dayAdded = !historyDisabled && doUpdate(connection, insertActivityDaySql, -1, stmt -> {
                stmt.setString(1, userId);
                stmt.setObject(2, day);
                stmt.setString(3, kind);
            }) > 0;
            commitAndPublishRecord(connection, userId, historyDisabled, dayAdded);
            return historyDisabled ? ActivityRecordOutcome.HISTORY_OFF : ActivityRecordOutcome.RECORDED;
        });
    }

    /// @return whether this call advanced the user's last-active instant to `activeAt`
    private boolean touchLastActive(Connection connection, String userId, Instant activeAt) throws SQLException {
        Timestamp at = Timestamp.from(activeAt);
        return doUpdate(connection, touchLastActiveSql, -1, stmt -> {
            stmt.setTimestamp(1, at);
            stmt.setString(2, userId);
            stmt.setTimestamp(3, at);
        }) > 0;
    }

    private boolean doSetActivityHistoryDisabled(String userId, boolean disabled) {
        return inTransaction("Failed to switch the activity history of user ", userId, connection -> {
            Boolean currentlyDisabled = selectActivityHistoryDisabled(connection, userId);
            if (currentlyDisabled == null) {
                return false;
            }
            if (currentlyDisabled != disabled) {
                doUpdate(connection, updateActivityHistoryDisabledSql, stmt -> {
                    stmt.setBoolean(1, disabled);
                    stmt.setString(2, userId);
                });
                int dayRows = disabled ? doUpdate(connection, deleteActivityDaysByUserSql, -1, stmt -> stmt.setString(1, userId)) : 0;
                commitAndPublishRecord(connection, userId, disabled, true);
                if (disabled) {
                    logger.info("[{}] Activity history disabled: {} activity day row(s) deleted", userId, dayRows);
                } else {
                    logger.info("[{}] Activity history enabled", userId);
                }
            }
            return true;
        });
    }

    private boolean doPutAttachment(String userId, String name, String json) {
        return inTransaction("Failed to store an attachment of user ", userId, connection -> {
            Boolean historyDisabled = selectActivityHistoryDisabled(connection, userId);
            if (historyDisabled == null) {
                return false;
            }
            boolean attachmentChanged = doUpdate(connection, upsertAttachmentSql, -1, stmt -> {
                stmt.setString(1, userId);
                stmt.setString(2, name);
                stmt.setString(3, json);
            }) > 0;
            commitAndPublishRecord(connection, userId, historyDisabled, attachmentChanged);
            return true;
        });
    }

    private int doPurgeActivityDaysBefore(LocalDate day) {
        return inTransaction("Failed to purge activity days before ", day, connection -> {
            int dayRows = doUpdate(connection, deleteActivityDaysBeforeSql, -1, stmt -> stmt.setObject(1, day));
            try {
                connection.commit();
            } catch (SQLException e) {
                // The commit may have deleted the activity days of any number of users, so no single user's record can be read back and published.
                publisher.failRecordSubscriptions(e);
                throw e;
            }
            if (dayRows > 0) {
                publisher.publishDaysPurged(day);
            }
            return dayRows;
        });
    }

    /// @return `null` when no row exists for `userId`; a soft-deleted user has a row
    private @Nullable UserRecord doReadRecord(String userId) {
        return withConnection("Failed to read the record of user ", userId, connection -> selectRecord(connection, userId));
    }

    /// Reads every user's record with three statements, which read:
    ///
    /// - whether each user's activity history is disabled;
    /// - the users' activity days from `earliestDay` on;
    /// - the users' attachments.
    ///
    /// The statements run on one connection outside a transaction. They read one consistent state, because every write to these tables runs as a task on
    /// the same executor as this read, one task at a time.
    ///
    /// @return every user's record, holding no activity day before `earliestDay`
    private List<UserRecord> doListRecords(LocalDate earliestDay) {
        return withConnection("Failed to list user records", connection -> {
            // The three statements return their rows in unrelated orders, so the builder for each activity-day row and attachment row is looked up in
            // `recordBuildersByUserId` by the row's user id.
            Map<String, UserRecordBuilder> recordBuildersByUserId = new HashMap<>();
            forEachRow(connection, selectAllActivityHistoryDisabledSql, _ -> {}, rs -> {
                String userId = rs.getString(1);
                recordBuildersByUserId.put(userId, new UserRecordBuilder(userId, rs.getBoolean(2)));
            });
            forEachRow(connection, selectAllActivityDaysSinceSql, stmt -> stmt.setObject(1, earliestDay), rs -> {
                UserRecordBuilder recordBuilder = recordBuildersByUserId.get(rs.getString(1));
                assert recordBuilder != null : "an activity day row references a user row";
                recordBuilder.addActivityDay(rs.getString(2), rs.getObject(3, LocalDate.class));
            });
            forEachRow(connection, selectAllAttachmentsSql, _ -> {}, rs -> {
                UserRecordBuilder recordBuilder = recordBuildersByUserId.get(rs.getString(1));
                assert recordBuilder != null : "an attachment row references a user row";
                recordBuilder.addAttachment(rs.getString(2), rs.getString(3));
            });
            var recordsBuilder = ImmutableList.<UserRecord>builderWithExpectedSize(recordBuildersByUserId.size());
            recordBuildersByUserId.values().forEach(recordBuilder -> recordsBuilder.add(recordBuilder.build()));
            return recordsBuilder.build();
        });
    }

    /// Commits a write to `userId`'s record and, when the write changed the record, publishes the record as the write left it. A commit that reports a
    /// failure may still have taken effect, so the record is then read again from the database and published before the failure propagates.
    ///
    /// @param historyDisabled whether the user's activity history is disabled as the write leaves it
    /// @param recordChanged   whether the write changed any value that a [UserRecord] holds
    private void commitAndPublishRecord(Connection connection, String userId, boolean historyDisabled, boolean recordChanged) throws SQLException {
        UserRecord record = recordChanged ? selectRecord(connection, userId, historyDisabled) : null;
        try {
            connection.commit();
        } catch (SQLException e) {
            if (recordChanged) {
                publishRecordAfterFailedCommit(userId);
            }
            throw e;
        }
        if (record != null) {
            publisher.publishRecordChanged(record);
        }
    }

    private void publishRecordAfterFailedCommit(String userId) {
        try (Connection connection = dataSource.getConnection()) {
            UserRecord record = selectRecord(connection, userId);
            assert record != null : "a write that changed a record found its user, and no other write has run since";
            publisher.publishRecordChanged(record);
        } catch (SQLException e) {
            publisher.failRecordSubscriptions(e);
            reportFailedCommitReadBack(userId, e);
        }
    }

    /// @return `null` when no row exists for `userId`; a soft-deleted user has a row
    private @Nullable UserRecord selectRecord(Connection connection, String userId) throws SQLException {
        Boolean historyDisabled = selectActivityHistoryDisabled(connection, userId);
        return historyDisabled == null ? null : selectRecord(connection, userId, historyDisabled);
    }

    /// Reads the record of a user whose row exists, with one statement for the user's activity days and one for the user's attachments.
    private UserRecord selectRecord(Connection connection, String userId, boolean historyDisabled) throws SQLException {
        var recordBuilder = new UserRecordBuilder(userId, historyDisabled);
        forEachRow(connection,
                   selectActivityDaysByUserSql,
                   stmt -> stmt.setString(1, userId),
                   rs -> recordBuilder.addActivityDay(rs.getString(1), rs.getObject(2, LocalDate.class)));
        forEachRow(connection,
                   selectAttachmentsByUserSql,
                   stmt -> stmt.setString(1, userId),
                   rs -> recordBuilder.addAttachment(rs.getString(1), rs.getString(2)));
        return recordBuilder.build();
    }

    /// @return `null` when no row exists for `userId`; a soft-deleted user has a row
    private @Nullable Boolean selectActivityHistoryDisabled(Connection connection, String userId) throws SQLException {
        return selectUserByIdUsing(connection, selectActivityHistoryDisabledSql, userId, rs -> rs.getBoolean(1));
    }

    private List<UserProfile> doListInactiveSince(Instant cutoff, int limit) {
        return queryList(selectInactiveSinceSql,
                         stmt -> {
                             stmt.setTimestamp(1, Timestamp.from(cutoff));
                             stmt.setInt(2, limit);
                         },
                         UserPersistenceImpl::mapUserProfile,
                         "Failed to list users inactive since " + cutoff);
    }

    private List<UserIdentityRecord> doListIdentities(String sql, String userId) {
        return queryList(sql, stmt -> stmt.setString(1, userId), UserPersistenceImpl::mapIdentityRecord, "Failed to list identities for user " + userId);
    }

    private <T> List<T> queryList(String sql, SqlConsumer<PreparedStatement> paramSetter, SqlFunction<ResultSet, T> rowMapper, String failureDescription) {
        return withConnection(failureDescription, connection -> {
            var resultBuilder = ImmutableList.<T>builder();
            forEachRow(connection, sql, paramSetter, rs -> resultBuilder.add(rowMapper.apply(rs)));
            return resultBuilder.build();
        });
    }

    /// Soft-deletes the user and the identities that must share their deletion timestamp, so [#restore] can match them again, in one transaction.
    ///
    /// @param cutoff when present, the user is deleted only if last active strictly before it, and a miss rolls back and is classified in the same
    ///               transaction; when `null` the delete is unconditional and idempotent, and the outcome is always
    ///               [ConditionalSoftDeleteOutcome#SOFT_DELETED]
    private ConditionalSoftDeleteOutcome softDeleteInTransaction(String userId, @Nullable Instant cutoff) {
        return inTransaction(cutoff == null ? "Failed to delete user " : "Failed to delete inactive user ", userId, connection -> {
            Instant now = persistenceNow();
            int userRows = doUpdate(connection,
                                    cutoff == null ? softDeleteUserSql : softDeleteUserIfInactiveSql,
                                    -1,
                                    stmt -> {
                                        stmt.setTimestamp(1, Timestamp.from(now));
                                        stmt.setTimestamp(2, Timestamp.from(now));
                                        stmt.setString(3, userId);
                                        if (cutoff != null) {
                                            stmt.setTimestamp(4, Timestamp.from(cutoff));
                                        }
                                    });
            if (cutoff != null && userRows == 0) {
                // Every write to the user table runs as a task on `executor`, one task at a time, so no write changes the user's row between the update above
                // and the read in `classifyMissedConditionalDelete`.
                ConditionalSoftDeleteOutcome outcome = classifyMissedConditionalDelete(connection, userId);
                connection.rollback();
                return outcome;
            }
            doUpdate(connection,
                     softDeleteIdentitiesSql,
                     -1,
                     stmt -> {
                         stmt.setTimestamp(1, Timestamp.from(now));
                         stmt.setTimestamp(2, Timestamp.from(now));
                         stmt.setString(3, userId);
                     });
            commitAndPublishState(connection, userId);
            return ConditionalSoftDeleteOutcome.SOFT_DELETED;
        });
    }

    /// Why the conditional statement matched nothing: the row is gone, it is already soft-deleted, or the user has been active since the cutoff. Only the last
    /// of those is about their activity, so the three cannot be collapsed.
    private ConditionalSoftDeleteOutcome classifyMissedConditionalDelete(Connection connection, String userId) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(selectUserDeletedAtSql)) {
            stmt.setString(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return ConditionalSoftDeleteOutcome.ABSENT;
                }
                return rs.getTimestamp("deleted_at") == null
                       ? ConditionalSoftDeleteOutcome.STILL_ACTIVE
                       : ConditionalSoftDeleteOutcome.ALREADY_SOFT_DELETED;
            }
        }
    }

    /// @return a future that completes once every subscribed [UserRecordListener] and [UserStateListener] has applied the removal, and completes
    ///         exceptionally if one of them fails to apply it
    private CompletableFuture<Void> doHardDelete(String userId) {
        return inTransaction("Failed hard-deleting user ", userId, connection -> {
            int activityDayRows = doUpdate(connection, deleteActivityDaysByUserSql, -1, stmt -> stmt.setString(1, userId));
            int attachmentRows = doUpdate(connection, deleteAttachmentsByUserSql, -1, stmt -> stmt.setString(1, userId));
            int identityRows = doUpdate(connection, hardDeleteIdentitiesSql, -1, stmt -> stmt.setString(1, userId));
            int userRows = doUpdate(connection, hardDeleteUserSql, -1, stmt -> stmt.setString(1, userId));
            // When this commit throws, the hard delete fails, and a repeated `hardDelete` call publishes the removal whether or not this commit took effect.
            connection.commit();
            logger.info("[{}] Hard-deleted: {} activity day row(s), {} attachment row(s), {} identity row(s), {} user row(s)",
                        userId, activityDayRows, attachmentRows, identityRows, userRows);
            return publisher.publishRemoved(userId);
        });
    }

    private void doRestore(String userId) {
        inTransaction("Failed to restore user ", userId, connection -> {
            UserProfileWithDeletion existingUser = selectUserByIdWithDeletion(connection, userId);
            Optional<Instant> deletedAt = existingUser == null ? Optional.empty() : existingUser.deletedAt();
            if (deletedAt.isPresent()) {
                Instant now = persistenceNow();
                doUpdate(connection, restoreUserSql, 1, stmt -> {
                    stmt.setTimestamp(1, Timestamp.from(now));
                    stmt.setString(2, userId);
                });
                // `restoreIdentitiesSql` restores the identities whose `deleted_at` equals the user's `deleted_at`, which are the identities soft-deleted
                // together with the user. An identity soft-deleted at any other time keeps its `deleted_at`.
                doUpdate(connection, restoreIdentitiesSql, -1, stmt -> {
                    stmt.setTimestamp(1, Timestamp.from(now));
                    stmt.setString(2, userId);
                    stmt.setTimestamp(3, Timestamp.from(deletedAt.get()));
                });
                commitAndPublishState(connection, userId);
            } else {
                // This branch writes nothing, so `existingUser` is the user's state after the commit, or `null` when the user has no row.
                commitAndPublish(connection, userId, existingUser);
            }
            return null;
        });
    }

    /// Runs `transaction` on a connection with auto-commit off, and rolls back any write that `transaction` leaves uncommitted.
    ///
    /// @param <T>         the type of the transaction's result
    /// @param transaction must commit what it writes
    /// @throws RuntimeException if `transaction` or obtaining the connection fails with an [SQLException]; its message is `failureDescriptionPrefix` followed
    ///                          by `failureSubject`
    private <T> T inTransaction(String failureDescriptionPrefix, Object failureSubject, SqlFunction<Connection, T> transaction) {
        return withConnection(failureDescriptionPrefix, failureSubject, connection -> {
            connection.setAutoCommit(false);
            try {
                return transaction.apply(connection);
            } catch (SQLException e) {
                rollbackQuietly(connection);
                throw e;
            } finally {
                endTransaction(connection);
            }
        });
    }

    /// Runs `work` on a connection of its own.
    ///
    /// @param <T> the type of the work's result
    /// @throws RuntimeException if `work` or obtaining the connection fails with an [SQLException]; its message is `failureDescription`
    private <T> T withConnection(String failureDescription, SqlFunction<Connection, T> work) {
        return withConnection(failureDescription, "", work);
    }

    /// Runs `work` on a connection of its own.
    ///
    /// @param <T> the type of the work's result
    /// @throws RuntimeException if `work` or obtaining the connection fails with an [SQLException]; its message is `failureDescriptionPrefix` followed by
    ///                          `failureSubject`
    private <T> T withConnection(String failureDescriptionPrefix, Object failureSubject, SqlFunction<Connection, T> work) {
        try {
            try (Connection connection = dataSource.getConnection()) {
                return work.apply(connection);
            }
        } catch (SQLException e) {
            throw new RuntimeException(failureDescriptionPrefix + failureSubject, e);
        }
    }

    /// Commits a write that changed only `userId`'s deletion state, and publishes the user as the write left them, or nothing when no row is left.
    private void commitAndPublishState(Connection connection, String userId) throws SQLException {
        commitAndPublish(connection, userId, selectUserByIdWithDeletion(connection, userId));
    }

    /// @param user the user as this transaction leaves them, or `null` when no row is left
    private void commitAndPublish(Connection connection, String userId, @Nullable UserProfileWithDeletion user) throws SQLException {
        commit(connection, userId, ImmutableList.of());
        publishState(user);
    }

    /// Commits a write to `userId`. A commit that reports a failure may still have taken effect, so what the database holds is then read afresh and published
    /// before the failure propagates.
    ///
    /// @param touchedIdentities the identities the write links to the user or unlinks from them
    private void commit(Connection connection, String userId, Iterable<UserIdentity> touchedIdentities) throws SQLException {
        try {
            connection.commit();
        } catch (SQLException e) {
            publishAfterFailedCommit(userId, touchedIdentities);
            throw e;
        }
    }

    private void publishAfterFailedCommit(String userId, Iterable<UserIdentity> touchedIdentities) {
        try (Connection connection = dataSource.getConnection()) {
            publishState(selectUserByIdWithDeletion(connection, userId));
            for (UserIdentity identity : publisher.subscribedIdentities(userId, touchedIdentities)) {
                publisher.publishResolution(identity, resolveUserByIdentity(connection, identity));
            }
        } catch (SQLException e) {
            reportFailedCommitReadBack(userId, e);
        }
    }

    private void reportFailedCommitReadBack(String userId, SQLException failure) {
        // `taskFailureReporter` raises an admin alert with no label, and the erasure cascade purges admin alerts by label, so the cascade would never purge a
        // user id held in this alert. The report therefore carries the failure without `userId`.
        logger.info("[{}] Failed reading back a user whose commit reported a failure", userId, failure);
        taskFailureReporter.onTaskException(FAILED_COMMIT_READ_BACK_TASK, failure);
    }

    /// @param user the user as a write left them, or `null` when it left no row, which publishes nothing
    private void publishState(@Nullable UserProfileWithDeletion user) {
        if (user != null) {
            publisher.publishChanged(user, ImmutableList.of(), ImmutableList.of());
        }
    }

    private @Nullable UserProfile selectUserByIdentity(Connection connection, UserIdentity identity) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(selectUserByIdentitySql)) {
            stmt.setString(1, identity.provider());
            stmt.setString(2, identity.providerUserId());
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? mapUserProfile(rs) : null;
            }
        }
    }

    private IdentityResolution resolveUserByIdentity(Connection connection, UserIdentity identity) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(resolveUserByIdentitySql)) {
            stmt.setString(1, identity.provider());
            stmt.setString(2, identity.providerUserId());
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return IdentityResolution.Absent.INSTANCE;
                }
                return IdentityResolution.createLinked(mapUserProfile(rs), rs.getTimestamp("deleted_at") != null);
            }
        }
    }

    private @Nullable UserProfile selectUserById(Connection connection, String userId) throws SQLException {
        return selectUserByIdUsing(connection, selectUserByIdSql, userId, UserPersistenceImpl::mapUserProfile);
    }

    /// @return `null` when no row exists for `userId`; a soft-deleted user has a row
    private @Nullable UserProfile selectUserByIdIgnoringDeletion(Connection connection, String userId) throws SQLException {
        return selectUserByIdUsing(connection, selectUserByIdIgnoringDeletionSql, userId, UserPersistenceImpl::mapUserProfile);
    }

    /// @return `null` when no row exists for `userId`; a soft-deleted user has a row
    private @Nullable UserProfileWithDeletion selectUserByIdWithDeletion(Connection connection, String userId) throws SQLException {
        return selectUserByIdUsing(connection, selectUserByIdWithDeletionSql, userId, UserPersistenceImpl::mapUserProfileWithDeletion);
    }

    /// Shared body of the by-id reads, which differ in whether their SQL filters out soft-deleted rows and in what `rowMapper` makes of the row.
    ///
    /// @return `null` when `sql` matches no row
    private static <T> @Nullable T selectUserByIdUsing(Connection connection, String sql, String userId, SqlFunction<ResultSet, T> rowMapper)
            throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rowMapper.apply(rs) : null;
            }
        }
    }

    private @Nullable IdentityLinkRecord selectIdentityByUserAndProvider(Connection connection,
                                                                         String userId,
                                                                         String provider) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(selectIdentityByUserAndProviderSql)) {
            stmt.setString(1, userId);
            stmt.setString(2, provider);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                String providerUserId = rs.getString(1);
                Timestamp deletedAt = rs.getTimestamp(2);
                return new IdentityLinkRecord(providerUserId, deletedAt == null);
            }
        }
    }

    private @Nullable String selectIdentityByProviderUserId(Connection connection, UserIdentity identity) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(selectIdentityByProviderUserIdSql)) {
            stmt.setString(1, identity.provider());
            stmt.setString(2, identity.providerUserId());
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private List<String> selectActiveIdentityProvidersByUser(Connection connection, String userId) throws SQLException {
        var providers = new ArrayList<String>(4);
        forEachRow(connection, selectActiveIdentityProvidersByUserSql, stmt -> stmt.setString(1, userId), rs -> providers.add(rs.getString(1)));
        return providers;
    }

    private static void forEachRow(Connection connection,
                                   String sql,
                                   SqlConsumer<PreparedStatement> paramSetter,
                                   SqlConsumer<ResultSet> rowConsumer) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            paramSetter.accept(stmt);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    rowConsumer.accept(rs);
                }
            }
        }
    }

    private void updateIdentity(Connection connection, String userId, UserIdentity identity, Instant updatedAt) throws SQLException {
        doUpdate(connection,
                 updateIdentitySql,
                 stmt -> {
                     stmt.setString(1, identity.providerUserId());
                     stmt.setTimestamp(2, null);
                     stmt.setTimestamp(3, Timestamp.from(updatedAt));
                     stmt.setString(4, userId);
                     stmt.setString(5, identity.provider());
                 });
    }

    private void updateIdentityDeletedAt(Connection connection,
                                         String userId,
                                         String provider,
                                         @Nullable Instant deletedAt,
                                         Instant updatedAt) throws SQLException {
        doUpdate(connection,
                 updateIdentityDeletedAtSql,
                 stmt -> {
                     // TODO: deletedAt is always null today; add coverage if identity deletion is introduced.
                     stmt.setTimestamp(1, deletedAt == null ? null : Timestamp.from(deletedAt));
                     stmt.setTimestamp(2, Timestamp.from(updatedAt));
                     stmt.setString(3, userId);
                     stmt.setString(4, provider);
                 });
    }

    /// The current instant at the resolution `timestamptz` stores, so a profile this class returns equals the one a later read produces. A raw
    /// [Instant] carries nanoseconds on Linux, which Postgres drops on write.
    private Instant persistenceNow() {
        return timeProvider.currentInstant().truncatedTo(ChronoUnit.MICROS);
    }

    private void insertUser(Connection connection, String userId, UserProfileInput profile, Instant now) throws SQLException {
        doUpdate(connection,
                 insertUserSql,
                 stmt -> {
                     stmt.setString(1, userId);
                     stmt.setString(2, profile.email());
                     stmt.setString(3, profile.displayName().orElse(null));
                     stmt.setString(4, profile.timezone().getId());
                     stmt.setTimestamp(5, Timestamp.from(now));
                     stmt.setTimestamp(6, Timestamp.from(now));
                     // Creating the account is itself activity, so a new row starts current rather than at the epoch — to the day, as every writer of this
                     // column is, so no reader has to ask which one produced a given value.
                     stmt.setTimestamp(7, Timestamp.from(now.truncatedTo(ChronoUnit.DAYS)));
                 });
    }

    private void insertIdentity(Connection connection, String userId, UserIdentity identity, Instant now) throws SQLException {
        doUpdate(connection,
                 insertIdentitySql,
                 stmt -> {
                     stmt.setString(1, userId);
                     stmt.setString(2, identity.provider());
                     stmt.setString(3, identity.providerUserId());
                     stmt.setTimestamp(4, Timestamp.from(now));
                     stmt.setTimestamp(5, Timestamp.from(now));
                 });
    }

    private static UserProfile mapUserProfile(ResultSet rs) throws SQLException {
        String id = rs.getString("id");
        String email = rs.getString("email");
        String displayName = rs.getString("display_name");
        String timezoneId = rs.getString("timezone");
        ZoneId timezone = ZoneId.of(timezoneId);
        Instant createdAt = rs.getTimestamp("created_at").toInstant();
        Instant updatedAt = rs.getTimestamp("updated_at").toInstant();
        return new UserProfile(id, email, Optional.ofNullable(displayName), timezone, createdAt, updatedAt);
    }

    private static UserProfileWithDeletion mapUserProfileWithDeletion(ResultSet rs) throws SQLException {
        return new UserProfileWithDeletion(mapUserProfile(rs), Optional.ofNullable(rs.getTimestamp("deleted_at")).map(Timestamp::toInstant));
    }

    private static UserIdentityRecord mapIdentityRecord(ResultSet rs) throws SQLException {
        String provider = rs.getString("provider");
        String providerUserId = rs.getString("provider_user_id");
        Instant createdAt = rs.getTimestamp("created_at").toInstant();
        Instant updatedAt = rs.getTimestamp("updated_at").toInstant();
        return new UserIdentityRecord(new UserIdentity(provider, providerUserId), createdAt, updatedAt);
    }

    private static List<String> mergeInitStatements(List<String> baseStatements, List<String> extraStatements) {
        if (extraStatements.isEmpty()) {
            return baseStatements;
        }
        return ImmutableList.<String>builderWithExpectedSize(baseStatements.size() + extraStatements.size())
                            .addAll(baseStatements)
                            .addAll(extraStatements)
                            .build();
    }

    private static boolean isUniqueViolation(SQLException exception) {
        return UNIQUE_VIOLATION_SQL_STATE.equals(exception.getSQLState());
    }

    private static void validateDistinctIdentityProviders(List<UserIdentity> identities) {
        var identitiesByProvider = LinkedHashMap.<String, UserIdentity>newLinkedHashMap(identities.size());
        for (UserIdentity identity : identities) {
            checkNotNull(identity, "identity");
            UserIdentity previousIdentity = identitiesByProvider.putIfAbsent(identity.provider(), identity);
            checkArgument(previousIdentity == null,
                          "Duplicate identity provider %s in %s and %s",
                          identity.provider(),
                          previousIdentity,
                          identity);
        }
    }

    private static void validateUserId(String userId) {
        checkNotNull(userId, "userId");
        checkArgument(!userId.isBlank(), "userId must not be blank");
    }

    /// Rolls back after a failed write. A rollback that itself fails returns the connection to the pool mid-transaction, so it is escalated; the failure that
    /// caused the rollback is reported by the caller in its own right.
    private void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            taskFailureReporter.onTaskException("rolling back a user data write", e);
        }
    }

    /// Discards anything still uncommitted before handing the connection back, then restores auto-commit. Every transaction here ends in a
    /// [Connection#commit()] on the success path, so the rollback finds nothing to undo; what it catches is a [RuntimeException] leaving the block after a
    /// write — a failed `checkState`, say — which [Connection#setAutoCommit(boolean)] would otherwise commit on the way out.
    private void endTransaction(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            taskFailureReporter.onTaskException("ending a user data transaction", e);
        }
        try {
            connection.setAutoCommit(true);
        } catch (SQLException ignored) {
        }
    }

    private static void doUpdate(Connection connection, String sql, SqlConsumer<PreparedStatement> paramSetter) throws SQLException {
        doUpdate(connection, sql, 1, paramSetter);
    }

    private static int doUpdate(Connection connection,
                                String sql,
                                int expectedRowsUpdated,
                                SqlConsumer<PreparedStatement> paramSetter) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            paramSetter.accept(stmt);
            logger.debug("Executing {}", sql);
            int rows = stmt.executeUpdate();
            if (expectedRowsUpdated >= 0) {
                checkState(rows == expectedRowsUpdated, "rows updated expected %s but was %s in %s", expectedRowsUpdated, rows, sql);
            }
            return rows;
        }
    }

    private interface SqlConsumer<T> {
        void accept(T target) throws SQLException;
    }

    private interface SqlFunction<T, R> {
        R apply(T target) throws SQLException;
    }

    private record IdentityLinkRecord(String providerUserId, boolean active) {
    }

    private static final class UserRecordBuilder {
        private final String userId;
        private final boolean activityHistoryDisabled;
        private final ImmutableSetMultimap.Builder<String, LocalDate> activityDaysByKindBuilder = ImmutableSetMultimap.builder();
        private final ImmutableMap.Builder<String, String> attachmentsByNameBuilder = ImmutableMap.builder();

        private UserRecordBuilder(String userId, boolean activityHistoryDisabled) {
            this.userId = checkNotNull(userId, "userId");
            this.activityHistoryDisabled = activityHistoryDisabled;
        }

        public void addActivityDay(String kind, LocalDate day) {
            activityDaysByKindBuilder.put(kind, day);
        }

        public void addAttachment(String name, String json) {
            attachmentsByNameBuilder.put(name, json);
        }

        public UserRecord build() {
            return new UserRecord(userId, activityDaysByKindBuilder.build(), activityHistoryDisabled, attachmentsByNameBuilder.build());
        }
    }
}

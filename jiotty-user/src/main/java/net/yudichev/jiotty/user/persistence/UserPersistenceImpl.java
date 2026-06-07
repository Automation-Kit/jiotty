package net.yudichev.jiotty.user.persistence;

import com.google.common.collect.ImmutableList;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.misc.UniqueId;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import net.yudichev.jiotty.persistence.db.CloseableDataSource;
import net.yudichev.jiotty.persistence.db.DataSourceFactory;
import net.yudichev.jiotty.persistence.domain.PersistenceDomain;
import net.yudichev.jiotty.persistence.domain.PersistenceDomainConfig;
import net.yudichev.jiotty.persistence.domain.PersistenceDomainMigrator;
import net.yudichev.jiotty.persistence.domain.PersistenceDomainService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.user.persistence.UserPersistenceModule.Dependency;
import static net.yudichev.jiotty.user.persistence.UserPersistenceModule.DomainName;
import static net.yudichev.jiotty.user.persistence.UserPersistenceModule.Executor;
import static net.yudichev.jiotty.user.persistence.UserPersistenceModule.InitStatements;
import static net.yudichev.jiotty.user.persistence.UserPersistenceModule.Migrator;
import static net.yudichev.jiotty.user.persistence.UserPersistenceModule.SchemaVersion;

@SuppressWarnings("JDBCPrepareStatementWithNonConstantString")
public final class UserPersistenceImpl extends BaseLifecycleComponent implements UserPersistence {
    private static final Logger logger = LogManager.getLogger(UserPersistenceImpl.class);
    private static final String UNIQUE_VIOLATION_SQL_STATE = "23505";

    private static final List<String> BASE_INIT_STATEMENTS = List.of(
            """
            CREATE TABLE IF NOT EXISTS %DOMAIN_PREFIX%user (
                id text PRIMARY KEY,
                email text UNIQUE,
                display_name text,
                timezone text NOT NULL,
                created_at timestamptz NOT NULL,
                updated_at timestamptz NOT NULL,
                deleted_at timestamptz
            );""",
            """
            CREATE TABLE IF NOT EXISTS %DOMAIN_PREFIX%identity (
                user_id text NOT NULL REFERENCES %DOMAIN_PREFIX%user(id),
                provider text NOT NULL,
                provider_user_id text NOT NULL,
                created_at timestamptz NOT NULL,
                updated_at timestamptz NOT NULL,
                deleted_at timestamptz,
                PRIMARY KEY (user_id, provider),
                UNIQUE (provider, provider_user_id)
            );""",
            "CREATE INDEX IF NOT EXISTS %DOMAIN_PREFIX%identity_user_id_idx ON %DOMAIN_PREFIX%identity (user_id);"
    );

    private final DataSourceFactory dataSourceFactory;
    private final Provider<SchedulingExecutor> executorProvider;
    private final PersistenceDomainService persistenceDomainService;
    private final CurrentDateTimeProvider timeProvider;
    private final PersistenceDomainConfig domainConfig;
    private final String selectUserByIdentitySql;
    private final String selectUserByIdSql;
    private final String selectAllUsersSql;
    private final String userExistsSql;
    private final String selectIdentityByUserAndProviderSql;
    private final String selectIdentityByProviderUserIdSql;
    private final String selectActiveIdentityProvidersByUserSql;
    private final String insertUserSql;
    private final String insertIdentitySql;
    private final String updateIdentitySql;
    private final String updateIdentityDeletedAtSql;
    private final String listIdentitiesSql;
    private final String softDeleteUserSql;
    private final String softDeleteIdentitiesSql;
    private final String hardDeleteUserSql;
    private final String hardDeleteIdentitiesSql;
    private final String selectUserDeletedAtSql;
    private final String restoreUserSql;
    private final String restoreIdentitiesSql;
    private final String updateUserSql;

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
                               @Migrator PersistenceDomainMigrator migrator) {
        this.dataSourceFactory = checkNotNull(dataSourceFactory, "dataSourceFactory");
        this.executorProvider = checkNotNull(executorProvider, "executorProvider");
        this.persistenceDomainService = checkNotNull(persistenceDomainService, "persistenceDomainService");
        this.timeProvider = checkNotNull(timeProvider, "timeProvider");
        checkArgument(schemaVersion > 0, "schemaVersion must be > 0, was %s", schemaVersion);
        var domain = new PersistenceDomain(checkNotNull(domainName, "domainName"));
        String userTable = domain.prefix() + "user";
        String identityTable = domain.prefix() + "identity";
        domainConfig = new PersistenceDomainConfig(domain,
                                                   schemaVersion,
                                                   mergeInitStatements(BASE_INIT_STATEMENTS, checkNotNull(initStatements, "initStatements")),
                                                   checkNotNull(migrator, "migrator"));
        selectUserByIdentitySql = "SELECT u.id, u.email, u.display_name, u.timezone, u.created_at, u.updated_at " +
                                  "FROM " + userTable + " u JOIN " + identityTable + " i ON u.id = i.user_id " +
                                  "WHERE i.provider=? AND i.provider_user_id=? AND i.deleted_at IS NULL AND u.deleted_at IS NULL";
        selectUserByIdSql = "SELECT id, email, display_name, timezone, created_at, updated_at FROM " + userTable +
                            " WHERE id=? AND deleted_at IS NULL";
        selectAllUsersSql = "SELECT id, email, display_name, timezone, created_at, updated_at FROM " + userTable +
                            " WHERE deleted_at IS NULL";
        userExistsSql = "SELECT 1 FROM " + userTable + " WHERE id=? AND deleted_at IS NULL";
        selectIdentityByUserAndProviderSql =
                "SELECT provider_user_id, deleted_at FROM " + identityTable + " WHERE user_id=? AND provider=?";
        selectIdentityByProviderUserIdSql =
                "SELECT user_id FROM " + identityTable + " WHERE provider=? AND provider_user_id=?";
        selectActiveIdentityProvidersByUserSql =
                "SELECT provider FROM " + identityTable + " WHERE user_id=? AND deleted_at IS NULL";
        insertUserSql = "INSERT INTO " + userTable + " (id, email, display_name, timezone, created_at, updated_at) VALUES (?,?,?,?,?,?)";
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
        softDeleteUserSql = "UPDATE " + userTable + " SET deleted_at=?, updated_at=? WHERE id=? AND deleted_at IS NULL";
        softDeleteIdentitiesSql =
                "UPDATE " + identityTable + " SET deleted_at=?, updated_at=? WHERE user_id=? AND deleted_at IS NULL";
        hardDeleteIdentitiesSql = "DELETE FROM " + identityTable + " WHERE user_id=?";
        hardDeleteUserSql = "DELETE FROM " + userTable + " WHERE id=?";
        selectUserDeletedAtSql = "SELECT deleted_at FROM " + userTable + " WHERE id=?";
        restoreUserSql = "UPDATE " + userTable + " SET deleted_at=NULL, updated_at=? WHERE id=? AND deleted_at IS NOT NULL";
        restoreIdentitiesSql = "UPDATE " + identityTable + " SET deleted_at=NULL, updated_at=? WHERE user_id=? AND deleted_at=?";
        updateUserSql = "UPDATE " + userTable + " SET email=?, display_name=?, timezone=?, updated_at=? WHERE id=? AND deleted_at IS NULL";
    }

    @Override
    protected void doStart() {
        executor = executorProvider.get();
        asUnchecked(() -> persistenceDomainService.ensureDomainReady(domainConfig).get(30, TimeUnit.SECONDS));
        dataSource = dataSourceFactory.create();
    }

    @Override
    protected void doStop() {
        Closeable.closeSafelyIfNotNull(logger, dataSource);
    }

    @Override
    public CompletableFuture<UserProfile> getOrCreateByIdentity(UserIdentity identity, UserProfileInput profile) {
        checkNotNull(identity, "identity");
        checkNotNull(profile, "profile");
        return whenStartedAndNotLifecycling(() -> executor.submit(() -> doGetOrCreateByIdentity(identity, profile)));
    }

    @Override
    public CompletableFuture<Optional<UserProfile>> getByIdentity(UserIdentity identity) {
        checkNotNull(identity, "identity");
        return whenStartedAndNotLifecycling(() -> executor.submit(() -> Optional.ofNullable(doGetByIdentity(identity))));
    }

    @Override
    public CompletableFuture<Optional<UserProfile>> getById(String userId) {
        validateUserId(userId);
        return whenStartedAndNotLifecycling(() -> executor.submit(() -> Optional.ofNullable(doGetById(userId))));
    }

    @Override
    public CompletableFuture<List<UserProfile>> listAllProfiles() {
        return whenStartedAndNotLifecycling(() -> executor.submit(this::doListAllProfiles));
    }

    @Override
    public CompletableFuture<UserProfile> updateProfile(String userId, UserProfileInput profile) {
        validateUserId(userId);
        checkNotNull(profile, "profile");
        return whenStartedAndNotLifecycling(() -> executor.submit(() -> doUpdateProfile(userId, profile)));
    }

    @Override
    public CompletableFuture<Void> updateAllIdentities(String userId, List<UserIdentity> identities) {
        validateUserId(userId);
        var identitiesCopy = ImmutableList.copyOf(checkNotNull(identities, "identities"));
        validateDistinctIdentityProviders(identitiesCopy);
        return whenStartedAndNotLifecycling(() -> executor.submit(() -> {
            doUpdateAllIdentities(userId, identitiesCopy);
            return null;
        }));
    }

    @Override
    public CompletableFuture<List<UserIdentityRecord>> listIdentities(String userId) {
        validateUserId(userId);
        return whenStartedAndNotLifecycling(() -> executor.submit(() -> doListIdentities(userId)));
    }

    @Override
    public CompletableFuture<Void> softDelete(String userId) {
        validateUserId(userId);
        return whenStartedAndNotLifecycling(() -> executor.submit(() -> {
            doSoftDelete(userId);
            return null;
        }));
    }

    @Override
    public CompletableFuture<Void> hardDelete(String userId) {
        validateUserId(userId);
        return whenStartedAndNotLifecycling(() -> executor.submit(() -> {
            doHardDelete(userId);
            return null;
        }));
    }

    @Override
    public CompletableFuture<Void> restore(String userId) {
        validateUserId(userId);
        return whenStartedAndNotLifecycling(() -> executor.submit(() -> {
            doRestore(userId);
            return null;
        }));
    }

    private UserProfile doGetOrCreateByIdentity(UserIdentity identity, UserProfileInput profile) {
        try {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    UserProfile existing = selectUserByIdentity(connection, identity);
                    if (existing != null) {
                        connection.commit();
                        return existing;
                    }
                    Instant now = timeProvider.currentInstant();
                    String userId = UniqueId.generate('u');
                    insertUser(connection, userId, profile, now);
                    insertIdentity(connection, userId, identity, now);
                    connection.commit();
                    return new UserProfile(userId, profile.email(), profile.displayName(), profile.timezone(), now, now);
                } catch (SQLException e) {
                    rollbackQuietly(connection);
                    if (!isUniqueViolation(e)) {
                        throw new RuntimeException("Failed creating user for identity " + identity, e);
                    }
                } finally {
                    resetAutoCommit(connection);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed creating user for identity " + identity, e);
        }

        UserProfile existing = doGetByIdentity(identity);
        checkState(existing != null, "Unable to create user for identity %s due to a uniqueness conflict", identity);
        return existing;
    }

    private @Nullable UserProfile doGetByIdentity(UserIdentity identity) {
        try {
            try (Connection connection = dataSource.getConnection()) {
                return selectUserByIdentity(connection, identity);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read user for identity " + identity, e);
        }
    }

    private @Nullable UserProfile doGetById(String userId) {
        try {
            try (Connection connection = dataSource.getConnection()) {
                return selectUserById(connection, userId);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read user " + userId, e);
        }
    }

    private List<UserProfile> doListAllProfiles() {
        try {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement stmt = connection.prepareStatement(selectAllUsersSql)) {
                try (ResultSet rs = stmt.executeQuery()) {
                    var result = new ArrayList<UserProfile>();
                    while (rs.next()) {
                        result.add(mapUserProfile(rs));
                    }
                    return result;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list user profiles", e);
        }
    }

    private UserProfile doUpdateProfile(String userId, UserProfileInput profile) {
        try {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    Instant now = timeProvider.currentInstant();
                    doUpdate(connection, updateUserSql, stmt -> {
                        stmt.setString(1, profile.email().orElse(null));
                        stmt.setString(2, profile.displayName().orElse(null));
                        stmt.setString(3, profile.timezone().getId());
                        stmt.setTimestamp(4, Timestamp.from(now));
                        stmt.setString(5, userId);
                    });
                    UserProfile updated = selectUserById(connection, userId);
                    checkState(updated != null, "User %s not found after update", userId);
                    connection.commit();
                    return updated;
                } catch (SQLException e) {
                    rollbackQuietly(connection);
                    throw new RuntimeException("Failed to update user " + userId, e);
                } finally {
                    resetAutoCommit(connection);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update user " + userId, e);
        }
    }

    private void doUpdateAllIdentities(String userId, List<UserIdentity> identities) {
        try {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    checkState(userExists(connection, userId), "User %s not found or deleted", userId);
                    Instant now = timeProvider.currentInstant();
                    var identitiesByProvider = LinkedHashMap.<String, UserIdentity>newLinkedHashMap(identities.size());
                    identities.forEach(identity -> identitiesByProvider.put(identity.provider(), identity));
                    for (UserIdentity identity : identitiesByProvider.values()) {
                        String existingUserId = selectIdentityByProviderUserId(connection, identity);
                        checkState(existingUserId == null || existingUserId.equals(userId), "%s already linked to another user", identity);
                        IdentityLinkRecord identityByProvider = selectIdentityByUserAndProvider(connection, userId, identity.provider());
                        if (identityByProvider == null) {
                            insertIdentity(connection, userId, identity, now);
                        } else if (!identityByProvider.providerUserId().equals(identity.providerUserId()) || !identityByProvider.active()) {
                            updateIdentity(connection, userId, identity, now);
                        }
                    }
                    for (String existingProvider : selectActiveIdentityProvidersByUser(connection, userId)) {
                        if (!identitiesByProvider.containsKey(existingProvider)) {
                            updateIdentityDeletedAt(connection, userId, existingProvider, now, now);
                        }
                    }
                    connection.commit();
                } catch (SQLException e) {
                    rollbackQuietly(connection);
                    throw new RuntimeException("Failed to update identities of user " + userId, e);
                } finally {
                    resetAutoCommit(connection);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update identities of user " + userId, e);
        }
    }

    private List<UserIdentityRecord> doListIdentities(String userId) {
        try {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement stmt = connection.prepareStatement(listIdentitiesSql)) {
                stmt.setString(1, userId);
                try (ResultSet rs = stmt.executeQuery()) {
                    var result = new ArrayList<UserIdentityRecord>();
                    while (rs.next()) {
                        result.add(mapIdentityRecord(rs));
                    }
                    return result;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list identities for user " + userId, e);
        }
    }

    private void doSoftDelete(String userId) {
        try {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    Instant now = timeProvider.currentInstant();
                    int rows = doUpdate(connection,
                                        softDeleteUserSql,
                                        -1,
                                        stmt -> {
                                            stmt.setTimestamp(1, Timestamp.from(now));
                                            stmt.setTimestamp(2, Timestamp.from(now));
                                            stmt.setString(3, userId);
                                        });
                    checkState(rows == 1, "User %s not found or already deleted", userId);
                    doUpdate(connection,
                             softDeleteIdentitiesSql,
                             -1,
                             stmt -> {
                                 stmt.setTimestamp(1, Timestamp.from(now));
                                 stmt.setTimestamp(2, Timestamp.from(now));
                                 stmt.setString(3, userId);
                             });
                    connection.commit();
                } catch (SQLException e) {
                    rollbackQuietly(connection);
                    throw new RuntimeException("Failed to delete user " + userId, e);
                } finally {
                    resetAutoCommit(connection);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete user " + userId, e);
        }
    }

    private void doHardDelete(String userId) {
        try {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    int identityRows = doUpdate(connection, hardDeleteIdentitiesSql, -1, stmt -> stmt.setString(1, userId));
                    int userRows = doUpdate(connection, hardDeleteUserSql, -1, stmt -> stmt.setString(1, userId));
                    connection.commit();
                    logger.info("Hard-deleted user {}: {} identity row(s), {} user row(s)", userId, identityRows, userRows);
                } catch (SQLException e) {
                    rollbackQuietly(connection);
                    throw new RuntimeException("Failed hard-deleting user " + userId, e);
                } finally {
                    resetAutoCommit(connection);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed hard-deleting user " + userId, e);
        }
    }

    private void doRestore(String userId) {
        try {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    Timestamp deletedAt = selectUserDeletedAt(connection, userId);
                    if (deletedAt == null) {
                        connection.commit(); // not soft-deleted: nothing to restore
                        return;
                    }
                    Instant now = timeProvider.currentInstant();
                    doUpdate(connection, restoreUserSql, 1, stmt -> {
                        stmt.setTimestamp(1, Timestamp.from(now));
                        stmt.setString(2, userId);
                    });
                    // Only revive the identities soft-deleted as part of this account deletion (same timestamp as the user), not ones removed earlier.
                    doUpdate(connection, restoreIdentitiesSql, -1, stmt -> {
                        stmt.setTimestamp(1, Timestamp.from(now));
                        stmt.setString(2, userId);
                        stmt.setTimestamp(3, deletedAt);
                    });
                    connection.commit();
                } catch (SQLException e) {
                    rollbackQuietly(connection);
                    throw new RuntimeException("Failed to restore user " + userId, e);
                } finally {
                    resetAutoCommit(connection);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to restore user " + userId, e);
        }
    }

    private @Nullable Timestamp selectUserDeletedAt(Connection connection, String userId) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(selectUserDeletedAtSql)) {
            stmt.setString(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getTimestamp("deleted_at") : null;
            }
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

    private @Nullable UserProfile selectUserById(Connection connection, String userId) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(selectUserByIdSql)) {
            stmt.setString(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? mapUserProfile(rs) : null;
            }
        }
    }

    private boolean userExists(Connection connection, String userId) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(userExistsSql)) {
            stmt.setString(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
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
        try (PreparedStatement stmt = connection.prepareStatement(selectActiveIdentityProvidersByUserSql)) {
            stmt.setString(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                var result = new ArrayList<String>(4);
                while (rs.next()) {
                    result.add(rs.getString(1));
                }
                return result;
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

    private void insertUser(Connection connection, String userId, UserProfileInput profile, Instant now) throws SQLException {
        doUpdate(connection,
                 insertUserSql,
                 stmt -> {
                     stmt.setString(1, userId);
                     stmt.setString(2, profile.email().orElse(null));
                     stmt.setString(3, profile.displayName().orElse(null));
                     stmt.setString(4, profile.timezone().getId());
                     stmt.setTimestamp(5, Timestamp.from(now));
                     stmt.setTimestamp(6, Timestamp.from(now));
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
        return new UserProfile(id, Optional.ofNullable(email), Optional.ofNullable(displayName), timezone, createdAt, updatedAt);
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
        var merged = new ArrayList<String>(baseStatements.size() + extraStatements.size());
        merged.addAll(baseStatements);
        merged.addAll(extraStatements);
        return ImmutableList.copyOf(merged);
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

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            logger.warn("Rollback failed", e);
        }
    }

    private static void resetAutoCommit(Connection connection) {
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

    @FunctionalInterface
    private interface SqlConsumer<T> {
        void accept(T target) throws SQLException;
    }

    private record IdentityLinkRecord(String providerUserId, boolean active) {
    }
}

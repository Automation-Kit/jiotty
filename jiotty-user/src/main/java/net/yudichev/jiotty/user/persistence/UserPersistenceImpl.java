package net.yudichev.jiotty.user.persistence;

import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.misc.UniqueId;
import net.yudichev.jiotty.persistence.db.CloseableDataSource;
import net.yudichev.jiotty.persistence.db.DataSourceFactory;
import net.yudichev.jiotty.persistence.domain.PersistenceDomain;
import net.yudichev.jiotty.persistence.domain.PersistenceDomainConfig;
import net.yudichev.jiotty.persistence.domain.PersistenceDomainMigrator;
import net.yudichev.jiotty.persistence.domain.PersistenceDomainService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
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
final class UserPersistenceImpl extends BaseLifecycleComponent implements UserPersistence {
    private static final Logger logger = LoggerFactory.getLogger(UserPersistenceImpl.class);
    private static final String UNIQUE_VIOLATION_SQL_STATE = "23505";

    private static final List<String> BASE_INIT_STATEMENTS = List.of(
            """
            CREATE TABLE IF NOT EXISTS %DOMAIN_PREFIX%user (
                id text PRIMARY KEY,
                email text UNIQUE,
                display_name text NOT NULL,
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
    private final PersistenceDomainConfig domainConfig;
    private final String selectUserByIdentitySql;
    private final String selectUserByIdSql;
    private final String selectAllUsersSql;
    private final String userExistsSql;
    private final String selectIdentityByUserAndProviderSql;
    private final String selectIdentityByProviderUserIdSql;
    private final String insertUserSql;
    private final String insertIdentitySql;
    private final String updateIdentityDeletedAtSql;
    private final String listIdentitiesSql;
    private final String softDeleteUserSql;
    private final String softDeleteIdentitiesSql;
    private final String updateUserSql;

    private SchedulingExecutor executor;
    private CloseableDataSource dataSource;

    @Inject
    UserPersistenceImpl(@Dependency DataSourceFactory dataSourceFactory,
                        @Executor Provider<SchedulingExecutor> executorProvider,
                        PersistenceDomainService persistenceDomainService,
                        @SchemaVersion int schemaVersion,
                        @DomainName String domainName,
                        @InitStatements List<String> initStatements,
                        @Migrator PersistenceDomainMigrator migrator) {
        this.dataSourceFactory = checkNotNull(dataSourceFactory);
        this.executorProvider = checkNotNull(executorProvider);
        this.persistenceDomainService = checkNotNull(persistenceDomainService);
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
        insertUserSql = "INSERT INTO " + userTable + " (id, email, display_name, timezone, created_at, updated_at) VALUES (?,?,?,?,?,?)";
        insertIdentitySql =
                "INSERT INTO " + identityTable + " (user_id, provider, provider_user_id, created_at, updated_at) VALUES (?,?,?,?,?)";
        updateIdentityDeletedAtSql =
                "UPDATE " + identityTable + " SET deleted_at=?, updated_at=? WHERE user_id=? AND provider=?";
        listIdentitiesSql =
                "SELECT i.provider, i.provider_user_id, i.created_at, i.updated_at FROM " + identityTable + " i " +
                "JOIN " + userTable + " u ON u.id = i.user_id " +
                "WHERE i.user_id=? AND i.deleted_at IS NULL AND u.deleted_at IS NULL";
        softDeleteUserSql = "UPDATE " + userTable + " SET deleted_at=?, updated_at=? WHERE id=? AND deleted_at IS NULL";
        softDeleteIdentitiesSql =
                "UPDATE " + identityTable + " SET deleted_at=?, updated_at=? WHERE user_id=? AND deleted_at IS NULL";
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
    public CompletableFuture<Optional<UserProfile>> getById(String userId) {
        validateUserId(userId);
        return whenStartedAndNotLifecycling(() -> executor.submit(() -> doGetById(userId)));
    }

    @Override
    public CompletableFuture<List<UserProfile>> listAllProfiles() {
        return whenStartedAndNotLifecycling(() -> executor.submit(this::doListAllProfiles));
    }

    @Override
    public CompletableFuture<UserProfile> updateProfile(String userId, UserProfileUpdate update) {
        validateUserId(userId);
        checkNotNull(update, "update");
        return whenStartedAndNotLifecycling(() -> executor.submit(() -> doUpdateProfile(userId, update)));
    }

    @Override
    public CompletableFuture<Void> linkIdentity(String userId, UserIdentity identity) {
        validateUserId(userId);
        checkNotNull(identity, "identity");
        return whenStartedAndNotLifecycling(() -> executor.submit(() -> {
            doLinkIdentity(userId, identity);
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

    private UserProfile doGetOrCreateByIdentity(UserIdentity identity, UserProfileInput profile) {
        try {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    Optional<UserProfile> existing = selectUserByIdentity(connection, identity);
                    if (existing.isPresent()) {
                        connection.commit();
                        return existing.get();
                    }
                    Instant now = Instant.now();
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

        Optional<UserProfile> existing = doGetByIdentity(identity);
        checkState(existing.isPresent(), "Unable to create user for identity %s due to a uniqueness conflict", identity);
        return existing.get();
    }

    private Optional<UserProfile> doGetByIdentity(UserIdentity identity) {
        try {
            try (Connection connection = dataSource.getConnection()) {
                return selectUserByIdentity(connection, identity);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read user for identity " + identity, e);
        }
    }

    private Optional<UserProfile> doGetById(String userId) {
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

    private UserProfile doUpdateProfile(String userId, UserProfileUpdate update) {
        try {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    Instant now = Instant.now();
                    doUpdate(connection, updateUserSql, stmt -> {
                        stmt.setString(1, update.email());
                        stmt.setString(2, update.displayName());
                        stmt.setString(3, update.timezone().getId());
                        stmt.setTimestamp(4, Timestamp.from(now));
                        stmt.setString(5, userId);
                    });
                    Optional<UserProfile> updated = selectUserById(connection, userId);
                    checkState(updated.isPresent(), "User %s not found after update", userId);
                    connection.commit();
                    return updated.get();
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

    private void doLinkIdentity(String userId, UserIdentity identity) {
        try {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    checkState(userExists(connection, userId), "User %s not found or deleted", userId);
                    Instant now = Instant.now();
                    Optional<IdentityLinkRecord> identityByProvider = selectIdentityByUserAndProvider(connection, userId, identity.provider());
                    if (identityByProvider.isPresent()) {
                        IdentityLinkRecord record = identityByProvider.get();
                        checkState(record.providerUserId().equals(identity.providerUserId()),
                                   "Provider %s is already linked with a different user id", identity.provider());
                        if (!record.active()) {
                            updateIdentityDeletedAt(connection, userId, identity.provider(), null, now);
                        }
                    } else {
                        Optional<String> existing = selectIdentityByProviderUserId(connection, identity);
                        checkState(existing.isEmpty(), "Identity %s already linked to another user", identity);
                        insertIdentity(connection, userId, identity, now);
                    }
                    connection.commit();
                } catch (SQLException e) {
                    rollbackQuietly(connection);
                    throw new RuntimeException("Failed to link identity " + identity + " to user " + userId, e);
                } finally {
                    resetAutoCommit(connection);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to link identity " + identity + " to user " + userId, e);
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
                    Instant now = Instant.now();
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

    private Optional<UserProfile> selectUserByIdentity(Connection connection, UserIdentity identity) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(selectUserByIdentitySql)) {
            stmt.setString(1, identity.provider());
            stmt.setString(2, identity.providerUserId());
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? Optional.of(mapUserProfile(rs)) : Optional.empty();
            }
        }
    }

    private Optional<UserProfile> selectUserById(Connection connection, String userId) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(selectUserByIdSql)) {
            stmt.setString(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? Optional.of(mapUserProfile(rs)) : Optional.empty();
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

    private Optional<IdentityLinkRecord> selectIdentityByUserAndProvider(Connection connection,
                                                                         String userId,
                                                                         String provider) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(selectIdentityByUserAndProviderSql)) {
            stmt.setString(1, userId);
            stmt.setString(2, provider);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String providerUserId = rs.getString(1);
                Timestamp deletedAt = rs.getTimestamp(2);
                return Optional.of(new IdentityLinkRecord(provider, providerUserId, deletedAt == null));
            }
        }
    }

    private Optional<String> selectIdentityByProviderUserId(Connection connection, UserIdentity identity) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(selectIdentityByProviderUserIdSql)) {
            stmt.setString(1, identity.provider());
            stmt.setString(2, identity.providerUserId());
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        }
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
                     stmt.setString(2, profile.email());
                     stmt.setString(3, profile.displayName());
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
        String id = checkNotNull(rs.getString("id"), "id");
        String email = rs.getString("email");
        String displayName = checkNotNull(rs.getString("display_name"), "display_name");
        String timezoneId = checkNotNull(rs.getString("timezone"), "timezone");
        ZoneId timezone = ZoneId.of(timezoneId);
        Instant createdAt = rs.getTimestamp("created_at").toInstant();
        Instant updatedAt = rs.getTimestamp("updated_at").toInstant();
        return new UserProfile(id, email, displayName, timezone, createdAt, updatedAt);
    }

    private static UserIdentityRecord mapIdentityRecord(ResultSet rs) throws SQLException {
        String provider = rs.getString("provider");
        String providerUserId = rs.getString("provider_user_id");
        Instant createdAt = rs.getTimestamp("created_at").toInstant();
        Instant updatedAt = rs.getTimestamp("updated_at").toInstant();
        return new UserIdentityRecord(new UserIdentity(provider, providerUserId), createdAt, updatedAt);
    }

    private static List<String> mergeInitStatements(List<String> baseStatements, List<String> extraStatements) {
        checkNotNull(baseStatements, "baseStatements");
        checkNotNull(extraStatements, "extraStatements");
        if (extraStatements.isEmpty()) {
            return baseStatements;
        }
        var merged = new ArrayList<String>(baseStatements.size() + extraStatements.size());
        merged.addAll(baseStatements);
        merged.addAll(extraStatements);
        return List.copyOf(merged);
    }

    private static boolean isUniqueViolation(SQLException exception) {
        return UNIQUE_VIOLATION_SQL_STATE.equals(exception.getSQLState());
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

    private record IdentityLinkRecord(String provider, String providerUserId, boolean active) {
        private IdentityLinkRecord {
            checkNotNull(provider, "provider");
            checkNotNull(providerUserId, "providerUserId");
            // TODO: provider blank cannot be produced via public API; add coverage if DB corruption handling is required.
            checkArgument(!provider.isBlank(), "provider must not be blank");
            // TODO: providerUserId blank is only reachable with DB corruption; keep coverage if corruption handling becomes a requirement.
            checkArgument(!providerUserId.isBlank(), "providerUserId must not be blank");
        }
    }
}

package net.yudichev.jiotty.persistence.varstore;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;

import static java.nio.file.Files.isRegularFile;
import static java.nio.file.Files.move;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;

final class FileToSqlVarStoreMigrator {
    private static final Logger logger = LogManager.getLogger(FileToSqlVarStoreMigrator.class);

    private FileToSqlVarStoreMigrator() {
    }

    static void migrate(Path filePath, SqlVarStore sqlVarStore) {
        Path renamedPath = filePath.resolveSibling(filePath.getFileName() + ".moved-to-database");

        if (!isRegularFile(filePath)) {
            if (isRegularFile(renamedPath)) {
                logger.info("Migration already completed: {} has been renamed to {}", filePath, renamedPath);
            } else {
                logger.info("No file to migrate at {}", filePath);
            }
            return;
        }

        logger.info("Migrating var store from {} to SQL table '{}'", filePath, sqlVarStore.tableName());
        asUnchecked(() -> {
            ObjectNode rootNode = BaseFileVarStore.OBJECT_MAPPER.readValue(filePath.toFile(), ObjectNode.class);
            String insertSql = "INSERT INTO " + sqlVarStore.tableName() + " (user_id, key, value, create_time, update_time) VALUES (?, ?, ?, ?, ?)";

            try (var connection = sqlVarStore.dataSource().getConnection()) {
                connection.setAutoCommit(false);
                //noinspection JDBCPrepareStatementWithNonConstantString
                try (var statement = connection.prepareStatement(insertSql)) {
                    var now = Timestamp.from(Instant.now());
                    for (var entry : rootNode.properties()) {
                        String compactValue = SqlVarStoreOperations.OBJECT_MAPPER.writeValueAsString(entry.getValue());
                        statement.setString(1, "");
                        statement.setString(2, entry.getKey());
                        statement.setString(3, compactValue);
                        statement.setTimestamp(4, now);
                        statement.setTimestamp(5, now);
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
                connection.commit();
            }

            move(filePath, renamedPath);
            logger.info("Migration complete, file renamed to {}", renamedPath);
        });
    }
}

package net.yudichev.jiotty.connector.tesla.teslamatedb;

import com.google.common.collect.ImmutableList;
import com.google.inject.BindingAnnotation;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.async.ExecutorFactory;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.geo.LatLon;
import net.yudichev.jiotty.common.geo.LatLonRectangle;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.BaseIdempotentCloseable;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.persistence.psql.CloseableDataSource;
import net.yudichev.jiotty.persistence.psql.PsqlDataSourceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verify;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

final class TeslamateDatabaseImpl extends BaseLifecycleComponent implements TeslamateDatabase {

    private static final Logger logger = LoggerFactory.getLogger(TeslamateDatabaseImpl.class);
    private static final String DRIVES_QUERY_SQL = """
                                                   SELECT
                                                      start_date,
                                                      end_date,
                                                      car.id as car_id,
                                                      start_position.latitude as start_lat,
                                                      start_position.longitude as start_long,
                                                      end_position.latitude as end_lat,
                                                      end_position.longitude as end_long,
                                                      duration_min,
                                                      drives.id as drive_id,
                                                      start_position.battery_level as start_battery_level,
                                                      end_position.battery_level as end_battery_level --,
                                                      -- outside_temp_avg,
                                                      -- speed_max
                                                    FROM drives
                                                    LEFT JOIN positions start_position ON start_position_id = start_position.id
                                                    LEFT JOIN positions end_position ON end_position_id = end_position.id
                                                    LEFT JOIN cars car ON car.id = drives.car_id
                                                    WHERE
                                                        drives.car_id = ? AND
                                                        start_date >= ? AND
                                                        end_date < ? AND
                                                        start_position.latitude between ? AND ? AND
                                                        start_position.longitude between ? AND ? AND
                                                        end_position.latitude between ? AND ? AND
                                                        end_position.longitude between ? AND ?
                                                    ORDER BY start_date""";

    private final PsqlDataSourceFactory dataSourceFactory;
    private final ExecutorFactory executorFactory;
    private final String vin;
    private SchedulingExecutor executor;

    @Inject
    public TeslamateDatabaseImpl(@Dependency PsqlDataSourceFactory dataSourceFactory, ExecutorFactory executorFactory, @Vin String vin) {
        this.dataSourceFactory = checkNotNull(dataSourceFactory);
        this.executorFactory = checkNotNull(executorFactory);
        this.vin = checkNotNull(vin);
    }

    @Override
    public Connection connect() {
        return new ConnectionImpl();
    }

    @Override
    protected void doStart() {
        executor = executorFactory.createSingleThreadedSchedulingExecutor("TeslamateDB");
    }

    @Override
    protected void doStop() {
        Closeable.closeSafelyIfNotNull(logger, executor);
    }

    @SuppressWarnings("DynamicRegexReplaceableByCompiledPattern") // for trace logging only
    private static String formatDrivesQuery(long carId,
                                            Timestamp from,
                                            Timestamp to,
                                            LatLonRectangle startRectangle,
                                            LatLonRectangle endRectangle) {
        return DRIVES_QUERY_SQL.replaceFirst("\\?", Long.toString(carId))
                               .replaceFirst("\\?", "'" + from + "'")
                               .replaceFirst("\\?", "'" + to + "'")
                               .replaceFirst("\\?", Double.toString(startRectangle.minLat()))
                               .replaceFirst("\\?", Double.toString(startRectangle.maxLat()))
                               .replaceFirst("\\?", Double.toString(startRectangle.minLon()))
                               .replaceFirst("\\?", Double.toString(startRectangle.maxLon()))
                               .replaceFirst("\\?", Double.toString(endRectangle.minLat()))
                               .replaceFirst("\\?", Double.toString(endRectangle.maxLat()))
                               .replaceFirst("\\?", Double.toString(endRectangle.minLon()))
                               .replaceFirst("\\?", Double.toString(endRectangle.maxLon()));
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Vin {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Dependency {
    }

    private class ConnectionImpl extends BaseIdempotentCloseable implements Connection {

        private final CloseableDataSource dataSource;
        private final long carId;

        public ConnectionImpl() {
            dataSource = dataSourceFactory.create();
            try (var connection = dataSource.getConnection();
                 var stmt = connection.prepareStatement("SELECT id FROM cars WHERE vin=?")) {
                stmt.setString(1, vin);
                try (ResultSet resultSet = stmt.executeQuery()) {
                    verify(resultSet.next(), "No cars found in TeslaMate database for the specified VIN");
                    carId = resultSet.getLong(1);
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public CompletableFuture<List<HistoricalDrive>> queryDrives(long computationId,
                                                                    Instant startInstant,
                                                                    Instant endInstant,
                                                                    LatLon startLocation,
                                                                    LatLon endLocation,
                                                                    double withinMeters) {
            checkState(!isClosed(), "Connection is closed");
            return executor.submit(() -> doQueryDrives(computationId, startInstant, endInstant, startLocation, endLocation, withinMeters));
        }

        @Override
        protected void doClose() {
            executor.execute(() -> Closeable.closeSafelyIfNotNull(logger, dataSource));
        }

        private List<HistoricalDrive> doQueryDrives(long computationId,
                                                    Instant startInstant,
                                                    Instant endInstant,
                                                    LatLon startLocation,
                                                    LatLon endLocation,
                                                    double withinMeters) {
            LatLonRectangle startRectangle = LatLonRectangle.create(startLocation, withinMeters);
            LatLonRectangle endRectangle = LatLonRectangle.create(endLocation, withinMeters);

            try (var connection = dataSource.getConnection();
                 var stmt = connection.prepareStatement(DRIVES_QUERY_SQL)) {
                int paramIdx = 1;
                stmt.setLong(paramIdx++, carId);
                stmt.setTimestamp(paramIdx++, Timestamp.from(startInstant));
                stmt.setTimestamp(paramIdx++, Timestamp.from(endInstant));
                stmt.setDouble(paramIdx++, startRectangle.minLat());
                stmt.setDouble(paramIdx++, startRectangle.maxLat());
                stmt.setDouble(paramIdx++, startRectangle.minLon());
                stmt.setDouble(paramIdx++, startRectangle.maxLon());
                stmt.setDouble(paramIdx++, endRectangle.minLat());
                stmt.setDouble(paramIdx++, endRectangle.maxLat());
                stmt.setDouble(paramIdx++, endRectangle.minLon());
                stmt.setDouble(paramIdx, endRectangle.maxLon());
                logger.debug("[c{}] Querying drives of car ID {} taken between {} and {} starting at {} ({}) and ending at {} ({}), within {}m",
                             computationId, carId, startInstant, endInstant, startLocation, startRectangle, endLocation, endRectangle, withinMeters);
                if (logger.isTraceEnabled()) {
                    logger.trace("[c{}] Query: {}",
                                 computationId,
                                 formatDrivesQuery(carId, Timestamp.from(startInstant), Timestamp.from(endInstant), startRectangle, endRectangle));
                }
                try (ResultSet rs = stmt.executeQuery()) {
                    var resultBuilder = ImmutableList.<HistoricalDrive>builder();
                    while (rs.next()) {
                        resultBuilder.add(HistoricalDrive.builder()
                                                         .setId(rs.getLong("drive_id"))
                                                         .setStartInstant(rs.getTimestamp("start_date").toInstant())
                                                         .setEndInstant(rs.getTimestamp("end_date").toInstant())
                                                         .setStartLocation(new LatLon(rs.getDouble("start_lat"), rs.getDouble("start_long")))
                                                         .setEndLocation(new LatLon(rs.getDouble("end_lat"), rs.getDouble("end_long")))
                                                         .setStartSoC(rs.getInt("start_battery_level"))
                                                         .setEndSoC(rs.getInt("end_battery_level"))
                                                         .build());
                    }
                    return resultBuilder.build();
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }

    }
}

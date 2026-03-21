package net.yudichev.jiotty.connector.google.drive;

import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.About;
import com.google.common.collect.ImmutableList;
import com.google.inject.BindingAnnotation;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static java.util.concurrent.CompletableFuture.supplyAsync;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;

final class GoogleDriveClientImpl extends BaseLifecycleComponent implements GoogleDriveClient {
    private static final Logger logger = LogManager.getLogger(GoogleDriveClientImpl.class);

    private static final String ROOT_ID = "root";
    private static final String APP_DATA_ID = "appDataFolder";
    private final Provider<Drive> driveProvider;
    private Drive drive;

    @Inject
    GoogleDriveClientImpl(@Dependency Provider<Drive> driveProvider) {
        this.driveProvider = checkNotNull(driveProvider);
    }

    @Override
    protected void doStart() {
        drive = driveProvider.get();
    }

    @Override
    public GoogleDrivePath getRootFolder(Executor executor) {
        return getFolder(ROOT_ID, executor);
    }

    @Override
    public GoogleDrivePath getAppDataFolder(Executor executor) {
        return getFolder(APP_DATA_ID, executor);
    }

    private InternalGoogleDrivePath getFolder(String id, Executor executor) {
        return whenStartedAndNotLifecycling(() -> new InternalGoogleDrivePath(drive, id, ImmutableList.of(), executor));
    }

    @Override
    public CompletableFuture<About> aboutDrive(Set<String> fields, Executor executor) {
        return whenStartedAndNotLifecycling(() -> supplyAsync(() -> getAsUnchecked(() -> {
                                                                  var result = drive.about().get()
                                                                                    .setFields(String.join(", ", fields))
                                                                                    .execute();
                                                                  logger.debug("aboutDrive: {}", result);
                                                                  return result;
                                                              }),
                                                              executor));
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Dependency {
    }
}

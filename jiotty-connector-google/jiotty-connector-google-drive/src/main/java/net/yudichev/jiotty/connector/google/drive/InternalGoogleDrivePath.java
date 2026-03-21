package net.yudichev.jiotty.connector.google.drive;

import com.google.api.client.http.ByteArrayContent;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.CompletableFuture.supplyAsync;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;

final class InternalGoogleDrivePath implements GoogleDrivePath {
    private static final String MIME_TYPE_FOLDER = "application/vnd.google-apps.folder";

    private static final Logger logger = LogManager.getLogger(InternalGoogleDrivePath.class);

    private final String id;
    private final List<String> path;
    private final Drive drive;
    private final Executor executor;

    InternalGoogleDrivePath(Drive drive, String id, List<String> path, Executor executor) {
        this.id = checkNotNull(id);
        this.path = checkNotNull(path);
        this.drive = checkNotNull(drive);
        this.executor = executor;
    }

    @Override
    public CompletableFuture<GoogleDrivePath> createSubFolder(String childFolderName) {
        logger.debug("createSubFolder {}, {}", this, childFolderName);
        return CompletableFuture.<GoogleDrivePath>supplyAsync(
                                        () -> getAsUnchecked(() ->
                                                                     new InternalGoogleDrivePath(drive, drive.files().create(new File()
                                                                                                                                     .setName(childFolderName)
                                                                                                                                     .setParents(ImmutableList.of(id))
                                                                                                                                     .setMimeType(MIME_TYPE_FOLDER))
                                                                                                             .setFields("id")
                                                                                                             .execute()
                                                                                                             .getId(),
                                                                                                 ImmutableList.<String>builder()
                                                                                                              .addAll(path)
                                                                                                              .add(childFolderName)
                                                                                                              .build(), executor)),
                                        executor)
                                .whenComplete((newSubFolder, throwable) ->
                                                      logger.debug("createSubFolder done: {}", newSubFolder, throwable));
    }

    @Override
    public CompletableFuture<GoogleDrivePath> createFile(String filename, String mimeType, byte[] fileData) {
        return supplyAsync(
                () -> getAsUnchecked(() -> {
                    var id = drive.files().create(
                                          new File()
                                                  .setName(filename)
                                                  .setParents(ImmutableList.of(this.id)),
                                          new ByteArrayContent(mimeType, fileData))
                                  .setFields("id")
                                  .execute()
                                  .getId();
                    logger.debug("Created {}/{}, id {}", path, filename, id);
                    return new InternalGoogleDrivePath(
                            drive,
                            id,
                            ImmutableList.<String>builder()
                                         .addAll(path)
                                         .add(filename)
                                         .build(), executor);
                }),
                executor);
    }

    @Override
    public CompletableFuture<Void> delete() {
        return supplyAsync(() -> getAsUnchecked(() -> {
            drive.files().delete(id).execute();
            logger.debug("Deleted {}", this);
            return null;
        }), executor);
    }

    @Override
    public CompletableFuture<Optional<GoogleDrivePath>> findFolderByPath(List<String> path) {
        return supplyAsync(
                () -> getAsUnchecked(() -> {
                    String parentId = id;
                    for (String pathElement : path) {
                        logger.debug("looking for folder {} with parentId {}", pathElement, parentId);
                        List<File> matchingFiles = drive.files().list()
                                                        .setQ(String.format("'%s' in parents and trashed=false and name = '%s' and mimeType = '%s'",
                                                                            parentId,
                                                                            pathElement,
                                                                            MIME_TYPE_FOLDER))
                                                        .execute()
                                                        .getFiles();
                        if (matchingFiles.isEmpty()) {
                            logger.debug("findFolderByPath: could not find folder {} with parentId {}, returning empty", pathElement, parentId);
                            return Optional.empty();
                        }
                        parentId = matchingFiles.getFirst().getId();
                        logger.debug("Found folder {} folder Id {}", pathElement, parentId);
                    }
                    return Optional.of(new InternalGoogleDrivePath(drive, parentId, ImmutableList.<String>builder()
                                                                                                 .addAll(this.path)
                                                                                                 .addAll(path)
                                                                                                 .build(), executor));
                }),
                executor);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("id", id)
                          .add("path", path)
                          .toString();
    }
}

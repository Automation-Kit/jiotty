package net.yudichev.jiotty.connector.google.photos;

import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.api.gax.rpc.UnaryCallable;
import com.google.photos.library.v1.PhotosLibraryClient;
import com.google.photos.library.v1.PhotosLibrarySettings;
import com.google.photos.library.v1.proto.BatchCreateMediaItemsRequest;
import com.google.photos.library.v1.proto.CreateAlbumRequest;
import com.google.photos.library.v1.proto.GetAlbumRequest;
import com.google.photos.library.v1.proto.ListAlbumsRequest;
import com.google.photos.library.v1.proto.NewMediaItemResult;
import com.google.photos.library.v1.upload.UploadMediaItemRequest;
import com.google.photos.library.v1.upload.UploadMediaItemResponse;
import com.google.photos.types.proto.Album;
import com.google.rpc.Code;
import com.google.rpc.Status;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.connector.google.common.GoogleAuthorization;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.function.IntConsumer;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.concurrent.CompletableFuture.supplyAsync;
import static net.yudichev.jiotty.common.lang.Closeable.closeIfNotNull;
import static net.yudichev.jiotty.common.lang.Closeable.idempotent;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;
import static net.yudichev.jiotty.connector.google.common.impl.Bindings.Authorization;

final class GooglePhotosClientImpl extends BaseLifecycleComponent implements GooglePhotosClient {
    private static final Logger logger = LogManager.getLogger(GooglePhotosClientImpl.class);
    private final Provider<GoogleAuthorization> googleAuthorizationProvider;
    private PhotosLibraryClient client;
    private Closeable closeable;

    @Inject
    GooglePhotosClientImpl(@Authorization Provider<GoogleAuthorization> googleAuthorizationProvider) {
        this.googleAuthorizationProvider = checkNotNull(googleAuthorizationProvider);
    }

    @Override
    public CompletableFuture<String> uploadMediaData(Path file, Executor executor) {
        return whenStartedAndNotLifecycling(() -> {
            var theClient = client;
            return supplyAsync(() -> {
                checkStarted();
                logger.debug("Started uploading {}", file);
                UploadMediaItemResponse uploadResponse;
                try (RandomAccessFile randomAccessFile = new RandomAccessFile(file.toFile(), "r")) {
                    UploadMediaItemRequest uploadRequest = UploadMediaItemRequest.newBuilder()
                                                                                 .setDataFile(randomAccessFile)
                                                                                 .setMimeType("application/octet-stream")
                                                                                 .build();
                    uploadResponse = callClient(theClient.uploadMediaItemCallable(), uploadRequest);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                uploadResponse.getError().ifPresent(error -> {throw new RuntimeException("Failed uploading " + file, error.getCause());});
                // guaranteed
                @SuppressWarnings("OptionalGetWithoutIsPresent")
                String uploadToken = uploadResponse.getUploadToken().get();
                logger.debug("Uploaded file {}, upload token {}", file, uploadToken);
                return uploadToken;
            }, executor);
        });
    }

    @Override
    public CompletableFuture<List<MediaItemOrError>> createMediaItems(Optional<String> albumId,
                                                                      List<NewMediaItem> newMediaItems,
                                                                      Executor executor) {
        return whenStartedAndNotLifecycling(() -> {
            var theClient = client;
            return supplyAsync(() -> {
                checkStarted();
                logger.debug("Create media items in album {}, items {}", albumId, newMediaItems);

                Stream<com.google.photos.library.v1.proto.NewMediaItem> newItems = newMediaItems.stream().map(BaseNewMediaItem::asGoogleMediaItem);

                BatchCreateMediaItemsRequest.Builder requestBuilder =
                        BatchCreateMediaItemsRequest.newBuilder().addAllNewMediaItems(newItems.collect(toImmutableList()));
                albumId.ifPresent(requestBuilder::setAlbumId);
                List<NewMediaItemResult> newMediaItemResultsList = callClient(theClient.batchCreateMediaItemsCallable(), requestBuilder.build())
                        .getNewMediaItemResultsList();

                checkState(newMediaItemResultsList.size() == newMediaItems.size(),
                           "expected media item creation result list size %s, got: %s", newMediaItems.size(), newMediaItemResultsList);

                return newMediaItemResultsList.stream()
                                              .map(newMediaItemResult -> {
                                                  Status status = newMediaItemResult.getStatus();
                                                  MediaItemOrError mediaItemOrError
                                                          = status.getCode() == Code.OK_VALUE
                                                            ? MediaItemOrError.item(new InternalGoogleMediaItem(newMediaItemResult.getMediaItem()))
                                                            : MediaItemOrError.error(status);
                                                  logger.debug("Finished uploading token {}, result {}",
                                                               newMediaItemResult.getUploadToken(),
                                                               newMediaItemResult);
                                                  return mediaItemOrError;
                                              })
                                              .collect(toImmutableList());
            }, executor);
        });
    }

    @Override
    public CompletableFuture<GooglePhotosAlbum> createAlbum(String name, Executor executor) {
        return whenStartedAndNotLifecycling(() -> {
            var theClient = client;
            return supplyAsync(() -> {
                logger.debug("Creating album '{}'", name);
                Album album = callClient(theClient.createAlbumCallable(), CreateAlbumRequest.newBuilder()
                                                                                            .setAlbum(Album.newBuilder()
                                                                                                           .setTitle(name)
                                                                                                           .build())
                                                                                            .build());
                logger.debug("Created album {}", album);
                return new InternalGooglePhotosAlbum(theClient, album);
            }, executor);
        });
    }

    @Override
    public CompletableFuture<List<GooglePhotosAlbum>> listAlbums(IntConsumer loadedAlbumCountProgressCallback, Executor executor) {
        return whenStartedAndNotLifecycling(() -> {
            var theClient = client;
            return supplyAsync(() -> {
                logger.debug("List all albums");
                PagedRequest<Album> request = new PagedRequest<>(logger, loadedAlbumCountProgressCallback, pageToken -> {
                    ListAlbumsRequest.Builder requestBuilder = ListAlbumsRequest.newBuilder()
                                                                                .setExcludeNonAppCreatedData(false)
                                                                                .setPageSize(50);
                    pageToken.ifPresent(requestBuilder::setPageToken);
                    return callClient(theClient.listAlbumsPagedCallable(), requestBuilder.build());
                });
                List<GooglePhotosAlbum> result = request.getAll().map(album -> new InternalGooglePhotosAlbum(theClient, album))
                                                        .collect(toImmutableList());
                logger.debug("Listed {} album(s)", result.size());
                return result;
            }, executor);
        });
    }

    @Override
    public CompletableFuture<GooglePhotosAlbum> getAlbum(String albumId, Executor executor) {
        return whenStartedAndNotLifecycling(() -> {
            var theClient = client;
            return supplyAsync(() -> {
                logger.debug("Get album info for albumId {}", albumId);
                Album album = callClient(theClient.getAlbumCallable(), GetAlbumRequest.newBuilder().setAlbumId(albumId).build());
                logger.debug("Got album info for albumId {}: {}", albumId, album);
                return new InternalGooglePhotosAlbum(theClient, album);
            }, executor);
        });
    }

    @Override
    protected void doStart() {
        client = getAsUnchecked(() -> PhotosLibraryClient.initialize(PhotosLibrarySettings.newBuilder()
                                                                                          .setCredentialsProvider(FixedCredentialsProvider.create(
                                                                                                  googleAuthorizationProvider.get().getCredentials()))
                                                                                          .build()));
        closeable = idempotent(() -> client.close());
    }

    // we must be interruptible; this is the only way (see https://github.com/google/java-photoslibrary/issues/43)
    // TODO simplify or remove if/when this is fixed
    private static <Q, R> R callClient(UnaryCallable<Q, R> callable, Q request) {
        try {
            return callable.futureCall(request).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    protected void doStop() {
        closeIfNotNull(closeable);
    }
}
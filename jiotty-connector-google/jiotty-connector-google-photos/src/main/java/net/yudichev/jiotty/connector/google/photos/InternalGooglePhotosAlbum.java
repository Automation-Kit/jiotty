package net.yudichev.jiotty.connector.google.photos;

import com.google.common.base.MoreObjects;
import com.google.photos.library.v1.PhotosLibraryClient;
import com.google.photos.library.v1.proto.SearchMediaItemsRequest;
import com.google.photos.types.proto.Album;
import com.google.photos.types.proto.MediaItem;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.IntConsumer;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.concurrent.CompletableFuture.supplyAsync;

final class InternalGooglePhotosAlbum implements GooglePhotosAlbum {
    private static final Logger logger = LogManager.getLogger(InternalGooglePhotosAlbum.class);
    private final PhotosLibraryClient client;
    private final Album album;

    InternalGooglePhotosAlbum(PhotosLibraryClient client, Album album) {
        this.client = checkNotNull(client);
        this.album = checkNotNull(album);
    }

    @Override
    public CompletableFuture<Void> addMediaItemsByIds(List<String> mediaItemsIds, Executor executor) {
        return supplyAsync(() -> {
            logger.debug("Adding to album {} items {}", album, mediaItemsIds);

            client.batchAddMediaItemsToAlbum(album.getId(), mediaItemsIds);

            logger.debug("Added");
            return null;
        }, executor);
    }

    @Override
    public CompletableFuture<Void> removeMediaItemsByIds(List<String> mediaItemsIds, Executor executor) {
        return supplyAsync(() -> {
            logger.debug("Removing from album {} items {}", album, mediaItemsIds);

            client.batchRemoveMediaItemsFromAlbum(album.getId(), mediaItemsIds);

            logger.debug("Removed");
            return null;
        }, executor);
    }

    @Override
    public String getTitle() {
        return album.getTitle();
    }

    @Override
    public String getId() {
        return album.getId();
    }

    @Override
    public long getMediaItemCount() {
        return album.getMediaItemsCount();
    }

    @Override
    public String getAlbumUrl() {
        return album.getProductUrl();
    }

    @Override
    public boolean isWriteable() {
        return album.getIsWriteable();
    }

    @Override
    public CompletableFuture<List<GoogleMediaItem>> getMediaItems(IntConsumer loadedItemCountProgressCallback, Executor executor) {
        return supplyAsync(() -> {
                               logger.debug("Get all media items in album {}", this);
                               PagedRequest<MediaItem> request = new PagedRequest<>(logger, loadedItemCountProgressCallback, pageToken -> {
                                   SearchMediaItemsRequest.Builder requestBuilder = SearchMediaItemsRequest.newBuilder()
                                                                                                           .setAlbumId(getId())
                                                                                                           .setPageSize(100);
                                   pageToken.ifPresent(requestBuilder::setPageToken);
                                   return client.searchMediaItems(requestBuilder.build());
                               });
                               List<GoogleMediaItem> result = request.getAll().map(InternalGoogleMediaItem::new)
                                                                     .collect(toImmutableList());
                               logger.debug("Got {} media item(s)", result.size());
                               return result;
                           },
                           executor);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        InternalGooglePhotosAlbum anotherAlbum = (InternalGooglePhotosAlbum) obj;
        return album.getId().equals(anotherAlbum.album.getId());
    }

    @Override
    public int hashCode() {
        return album.getId().hashCode();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("album", album)
                          .toString();
    }
}

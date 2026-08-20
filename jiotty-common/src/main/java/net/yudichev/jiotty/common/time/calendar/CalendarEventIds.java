package net.yudichev.jiotty.common.time.calendar;

import org.jspecify.annotations.Nullable;

import java.security.MessageDigest;

import static java.nio.charset.StandardCharsets.UTF_8;
import static net.yudichev.jiotty.common.lang.Sha256Digests.encodeUrl;
import static net.yudichev.jiotty.common.lang.Sha256Digests.resetDigest;

/// Identity for a calendar event whose provider gave it none of its own.
public final class CalendarEventIds {
    /// 72 bits of the digest, which distinguishes the entries of any one calendar while keeping the id short enough to key a stored record by.
    private static final int ID_LENGTH = 12;

    private CalendarEventIds() {
    }

    /// Derives an id from the event's own content, hashed so that no title or location reaches whatever the id ends up stored in. Two entries with the same
    /// title and location share an id, which is as far as content can distinguish them.
    ///
    /// @param summary  the entry's title
    /// @param location the entry's location, or `null` where it declares none, which derives the id from the title alone
    public static String createContentDerivedId(String summary, @Nullable String location) {
        MessageDigest digest = resetDigest();
        digest.update(summary.getBytes(UTF_8));
        // NUL separates the two fields, which a calendar entry's own text cannot contain, so no pair of values can hash as another pair would.
        digest.update((byte) 0);
        if (location != null) {
            digest.update(location.getBytes(UTF_8));
        }
        return encodeUrl(digest).substring(0, ID_LENGTH);
    }
}

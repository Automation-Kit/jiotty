package net.yudichev.jiotty.persistence.varstore;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

final class Utils {

    public static void validateUserId(String userId) {
        checkNotNull(userId, "userId");
        checkArgument(!userId.isBlank(), "userId must not be blank");
        checkArgument(!userId.contains("."), "userId must not contain dots: %s", userId);
    }
}

package net.yudichev.jiotty.user.persistence;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/// Provider identity used to authenticate a user.
///
/// @param provider       provider identifier (for example `firebase`, `google.com`, `apple.com`)
/// @param providerUserId provider-specific unique user id
public record UserIdentity(String provider, String providerUserId) {
    public UserIdentity {
        checkNotNull(provider, "provider");
        checkNotNull(providerUserId, "providerUserId");
        checkArgument(!provider.isBlank(), "provider must not be blank");
        checkArgument(!providerUserId.isBlank(), "providerUserId must not be blank");
    }
}

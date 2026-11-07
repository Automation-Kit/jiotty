package net.yudichev.jiotty.connector.firebase;

import net.yudichev.jiotty.user.persistence.UserIdentity;
import net.yudichev.jiotty.user.persistence.UserIdentityRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FirebaseAuthConnectorTest {
    private static final Instant RECORDED_AT = Instant.parse("2026-03-13T12:34:56Z");

    @Test
    void firebaseUidOfPicksTheFirebaseIdentityAmongTheOthers() {
        List<UserIdentityRecord> identities = List.of(createIdentityRecord(FirebaseAuthConnector.GOOGLE_PROVIDER_ID, "104857600000000000001"),
                                                      createIdentityRecord(FirebaseAuthConnector.IDENTITY_PROVIDER, "firebase-user-1"));

        assertThat(FirebaseAuthConnector.firebaseUidOf(identities)).contains("firebase-user-1");
    }

    @Test
    void firebaseUidOfIsEmptyWithoutAFirebaseIdentity() {
        assertThat(FirebaseAuthConnector.firebaseUidOf(List.of(createIdentityRecord(FirebaseAuthConnector.GOOGLE_PROVIDER_ID,
                                                                                    "104857600000000000001")))).isEmpty();
    }

    private static UserIdentityRecord createIdentityRecord(String provider, String providerUserId) {
        return new UserIdentityRecord(new UserIdentity(provider, providerUserId), RECORDED_AT, RECORDED_AT);
    }
}

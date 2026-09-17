package net.yudichev.jiotty.user.persistence.testing;

import net.yudichev.jiotty.user.persistence.BaseUserChangeContractTest;
import net.yudichev.jiotty.user.persistence.UserPersistence;
import org.junit.jupiter.api.BeforeEach;

/// Runs the shared change-stream contract against [FakeUserPersistence], so the double cannot drift from the store every other test trusts it to imitate.
class FakeUserChangeContractTest extends BaseUserChangeContractTest {
    private FakeUserPersistence userPersistence;

    @BeforeEach
    void setUp() {
        clock.setTime(START);
        userPersistence = new FakeUserPersistence(clock);
    }

    @Override
    protected UserPersistence userPersistence() {
        return userPersistence;
    }
}

package net.yudichev.jiotty.user.persistence;

import org.junit.jupiter.api.BeforeEach;

/// Runs [BaseUserRecordContractTest] against [FakeUserPersistence]. [UserPersistenceImplRecordContractTest] runs the same tests against [UserPersistenceImpl].
class FakeUserRecordContractTest extends BaseUserRecordContractTest {
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

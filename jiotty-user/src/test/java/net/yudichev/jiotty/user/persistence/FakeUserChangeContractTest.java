package net.yudichev.jiotty.user.persistence;

import org.junit.jupiter.api.BeforeEach;

/// Runs [BaseUserChangeContractTest] against [FakeUserPersistence], which tests of other code use in place of [UserPersistenceImpl].
/// [UserPersistenceImplChangeContractTest] runs the same tests against [UserPersistenceImpl].
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

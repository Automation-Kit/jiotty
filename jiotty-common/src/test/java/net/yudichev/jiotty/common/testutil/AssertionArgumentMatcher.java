package net.yudichev.jiotty.common.testutil;

import org.jspecify.annotations.Nullable;
import org.mockito.ArgumentMatcher;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.mockito.ArgumentMatchers.argThat;

public final class AssertionArgumentMatcher<T> implements ArgumentMatcher<T> {
    private final ArgumentAssert<T> argumentAssert;
    private @Nullable String assertionErrorMessage;

    private AssertionArgumentMatcher(ArgumentAssert<T> argumentAssert) {
        this.argumentAssert = checkNotNull(argumentAssert);
    }

    @Override
    public boolean matches(T argument) {
        try {
            argumentAssert.doAssert(argument);
            return true;
        } catch (AssertionError e) {
            assertionErrorMessage = e.getMessage();
            return false;
        }
    }

    @Override
    public String toString() {
        return assertionErrorMessage == null ? "matched" : assertionErrorMessage;
    }

    public static <T> T assertArg(ArgumentAssert<T> argumentAssert) {
        return argThat(new AssertionArgumentMatcher<>(argumentAssert));
    }

    @FunctionalInterface
    public interface ArgumentAssert<T> {
        void doAssert(T argument) throws AssertionError;
    }
}

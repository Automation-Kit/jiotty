package net.yudichev.jiotty.common.lang;

import jakarta.annotation.Nullable;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

public final class Either<L, R> {
    @Nullable
    private final L left;
    @Nullable
    private final R right;
    private final boolean isLeft;

    private Either(@Nullable L left, @Nullable R right, boolean isLeft) {
        this.left = left;
        this.right = right;
        this.isLeft = isLeft;
    }

    public static <L, R> Either<L, R> left(L value) {
        return new Either<>(value, null, true);
    }

    public static <L, R> Either<L, R> right(R value) {
        return new Either<>(null, value, false);
    }

    public void accept(Consumer<? super L> leftConsumer,
                       Consumer<? super R> rightConsumer) {
        if (isLeft) {
            leftConsumer.accept(left);
        } else {
            rightConsumer.accept(right);
        }
    }

    public <T> T map(Function<? super L, ? extends T> leftMapper,
                     Function<? super R, ? extends T> rightMapper) {
        return isLeft ? leftMapper.apply(left) : rightMapper.apply(right);
    }

    @SuppressWarnings("unchecked")
    public <T> Either<T, R> mapLeft(Function<? super L, ? extends T> leftMapper) {
        return isLeft ? left(leftMapper.apply(left)) : (Either<T, R>) this;
    }

    @SuppressWarnings("unchecked")
    public <T> Either<L, T> mapRight(Function<? super R, ? extends T> rightMapper) {
        return isLeft ? (Either<L, T>) this : right(rightMapper.apply(right));
    }

    public Optional<L> getLeft() {
        return Optional.ofNullable(left);
    }

    public Optional<R> getRight() {
        return Optional.ofNullable(right);
    }

    public L leftOrThrow(Function<? super R, ? extends RuntimeException> exceptionFactory) {
        if (left == null) {
            throw exceptionFactory.apply(right);
        }
        return left;
    }

    public R rightOrThrow(Function<? super L, ? extends RuntimeException> exceptionFactory) {
        if (right == null) {
            throw exceptionFactory.apply(left);
        }
        return right;
    }

    @Override
    public String toString() {
        return map(Objects::toString, Objects::toString);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        Either<?, ?> either = (Either<?, ?>) obj;
        return Objects.equals(left, either.left) &&
               Objects.equals(right, either.right);
    }

    @SuppressWarnings("ObjectInstantiationInEqualsHashCode")
    @Override
    public int hashCode() {
        return Objects.hash(left, right);
    }
}
package net.yudichev.jiotty.common.lang;

public interface ThrowingBiConsumer<T, U, E extends Throwable> {

    void accept(T input1, U input2) throws E;
}
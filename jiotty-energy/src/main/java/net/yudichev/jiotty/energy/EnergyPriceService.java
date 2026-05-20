package net.yudichev.jiotty.energy;

import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.Either;
import net.yudichev.jiotty.common.security.AuthState;

import java.util.Optional;
import java.util.function.Consumer;

public interface EnergyPriceService {
    Optional<Either<Prices, Failure>> getResult();

    Closeable subscribeToPrices(Consumer<Either<Prices, Failure>> consumer);

    Closeable subscribeToAuthState(Consumer<AuthState> consumer);

    sealed interface Failure permits Failure.IncompatibleTariff, Failure.PriceRetrievalError {
        record IncompatibleTariff(String activeTariffCode) implements Failure {}

        record PriceRetrievalError(Throwable cause) implements Failure {}
    }
}

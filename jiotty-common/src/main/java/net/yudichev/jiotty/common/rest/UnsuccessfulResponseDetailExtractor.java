package net.yudichev.jiotty.common.rest;

import net.yudichev.jiotty.common.lang.StringFormattable;
import okhttp3.Call;

import java.util.Optional;
import java.util.function.Function;

/// Chooses what [HttpResponseException#body()] carries in place of [RestClients#WITHHELD_BODY] when a call made through
/// [RestClients#callSuppressingResponseBody(Call, Function, int, StringFormattable, UnsuccessfulResponseDetailExtractor)] gets an unsuccessful response: a
/// part of the response's body that holds no personal data, such as an API's machine-readable error code.
public interface UnsuccessfulResponseDetailExtractor {
    /// Extracts nothing, so the failure carries [RestClients#WITHHELD_BODY].
    UnsuccessfulResponseDetailExtractor NONE = (_, _) -> Optional.empty();

    /// @param statusCode the unsuccessful response's status code
    /// @param body       the unsuccessful response's body
    /// @return the part of `body` to carry in place of [RestClients#WITHHELD_BODY], or empty to carry [RestClients#WITHHELD_BODY]
    /// @implSpec The part returned must hold no personal data, and the method must not throw: a body that is not in the expected shape, as a proxy's error
    /// page is, must yield empty.
    Optional<String> extract(int statusCode, String body);
}

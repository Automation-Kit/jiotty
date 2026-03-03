package net.yudichev.jiotty.connector.sonyprojector;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

final class SonyProjectorProtocol {
    private static final Charset CHARSET = StandardCharsets.US_ASCII;
    private static final String NO_KEY_RESPONSE = "NOKEY";
    private static final String OK_RESPONSE = "ok";
    private static final String AUTH_OK_RESPONSE = "OK";
    private static final String POWER_STATUS_PREFIX = "power_status";
    private static final char QUOTE = '"';
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final ThreadLocal<MessageDigest> SHA_256_DIGEST = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not supported", e);
        }
    });

    private SonyProjectorProtocol() {
    }

    public static boolean isNoKeyResponse(String response) {
        return NO_KEY_RESPONSE.equals(trim(response));
    }

    public static boolean isOkResponse(String response) {
        return OK_RESPONSE.equals(trim(response));
    }

    public static boolean isAuthOkResponse(String response) {
        return AUTH_OK_RESPONSE.equals(trim(response));
    }

    public static String computeAuthorisation(String challenge, String password) {
        checkNotNull(challenge);
        checkNotNull(password);
        var digest = SHA_256_DIGEST.get();
        byte[] bytes = (challenge + password).getBytes(CHARSET);
        digest.reset();
        byte[] hash = digest.digest(bytes);
        return toHex(hash);
    }

    public static SonyProjectorPowerState parsePowerStatusResponse(String response) {
        String trimmed = trim(response);
        checkArgument(!trimmed.startsWith("err_") && !"err".equals(trimmed), "Unexpected response: %s", response);
        String value;
        if (trimmed.startsWith(POWER_STATUS_PREFIX)) {
            int firstQuote = trimmed.indexOf(QUOTE);
            int lastQuote = trimmed.lastIndexOf(QUOTE);
            checkArgument(firstQuote >= 0 && lastQuote > firstQuote, "Invalid power status response: %s", response);
            value = trimmed.substring(firstQuote + 1, lastQuote);
        } else if (trimmed.length() > 1 && trimmed.charAt(0) == QUOTE && trimmed.charAt(trimmed.length() - 1) == QUOTE) {
            value = trimmed.substring(1, trimmed.length() - 1);
        } else {
            value = trimmed;
        }
        checkArgument(!value.isEmpty(), "Invalid power status response: %s", response);
        return SonyProjectorPowerState.fromProtocolValue(value);
    }

    private static String toHex(byte[] bytes) {
        var builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            int unsigned = value & 0xff;
            builder.append(HEX[unsigned >>> 4]);
            builder.append(HEX[unsigned & 0x0f]);
        }
        return builder.toString();
    }

    private static String trim(String input) {
        return input.trim();
    }
}

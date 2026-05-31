package net.yudichev.jiotty.common.lang;

import com.google.common.base.Throwables;
import org.jspecify.annotations.Nullable;

import java.util.List;

import static com.google.common.base.Preconditions.checkNotNull;

public final class HumanReadableExceptionMessage {
    public static String humanReadableMessage(Throwable exception) {
        var sb = new StringBuilder(128);
        var appender = Appender.wrap(sb);
        appendHumanReadableMessage(exception, appender);

        return sb.toString();
    }

    public static void appendHumanReadableMessage(Throwable exception, Appender appender) {
        List<Throwable> causalChain = Throwables.getCausalChain(exception);
        Throwable parent = null;
        boolean appended = false;
        for (Throwable throwable : causalChain) {
            String parentExceptionMessage;
            if (parent != null && !throwable.toString().equals(parentExceptionMessage = exceptionMessage(parent))) {
                appended = append(appended, appender, parent, parentExceptionMessage);
            }
            parent = throwable;
        }
        append(appended, appender, checkNotNull(parent), exceptionMessage(parent));
    }

    private static @Nullable String exceptionMessage(Throwable exception) {
        return exception instanceof InterruptedException ? null : exception.getMessage();
    }

    private static boolean append(boolean appended, Appender appender, Throwable throwable, @Nullable String message) {
        if (appended) {
            appender.append(": ");
        }
        boolean typeAppended;
        if (throwable.getClass() == RuntimeException.class) {
            typeAppended = false;
        } else {
            switch (throwable) {
                case Exception e -> appendType(appender, e, "Exception".length());
                case Error e -> appendType(appender, e, "Error".length());
                default -> appendType(appender, throwable, 0);
            }
            typeAppended = true;
        }
        appended |= typeAppended;
        if (message != null) {
            if (typeAppended) {
                appender.append(": ");
            }
            appender.append(message);
            appended = true;
        }
        return appended;
    }

    private static void appendType(Appender appender, Throwable throwable, int suffixLength) {
        String simpleName = throwable.getClass().getSimpleName();
        appender.append(simpleName, 0, simpleName.length() - suffixLength);
    }
}

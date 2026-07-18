package net.yudichev.jiotty.common.lang;

import com.google.common.base.Throwables;
import org.jspecify.annotations.Nullable;

import java.util.List;

import static com.google.common.base.Preconditions.checkNotNull;

public final class HumanReadableExceptionMessage {
    public static String humanReadableMessage(Throwable exception) {
        var sb = new StringBuilder(128);
        appendHumanReadableMessage(exception, sb);

        return sb.toString();
    }

    public static StringFormattable humanReadableMessageFormattable(Throwable exception) {
        return appendable -> appendHumanReadableMessage(exception, appendable);
    }

    public static void appendHumanReadableMessage(Throwable exception, Appendable appendable) {
        List<Throwable> causalChain = Throwables.getCausalChain(exception);
        Throwable parent = null;
        boolean appended = false;
        for (Throwable throwable : causalChain) {
            String parentExceptionMessage;
            if (parent != null && !throwable.toString().equals(parentExceptionMessage = exceptionMessage(parent))) {
                appended = append(appended, appendable, parent, parentExceptionMessage);
            }
            parent = throwable;
        }
        append(appended, appendable, checkNotNull(parent), exceptionMessage(parent));
    }

    private static @Nullable String exceptionMessage(Throwable exception) {
        return exception instanceof InterruptedException ? null : exception.getMessage();
    }

    private static boolean append(boolean appended, Appendable appendable, Throwable throwable, @Nullable String message) {
        if (appended) {
            Append.to(appendable, ": ");
        }
        boolean typeAppended;
        if (throwable.getClass() == RuntimeException.class) {
            typeAppended = false;
        } else {
            switch (throwable) {
                case Exception e -> appendType(appendable, e, "Exception".length());
                case Error e -> appendType(appendable, e, "Error".length());
                default -> appendType(appendable, throwable, 0);
            }
            typeAppended = true;
        }
        appended |= typeAppended;
        if (message != null) {
            if (typeAppended) {
                Append.to(appendable, ": ");
            }
            Append.to(appendable, message);
            appended = true;
        }
        return appended;
    }

    private static void appendType(Appendable appendable, Throwable throwable, int suffixLength) {
        String simpleName = throwable.getClass().getSimpleName();
        Append.to(appendable, simpleName, 0, simpleName.length() - suffixLength);
    }
}

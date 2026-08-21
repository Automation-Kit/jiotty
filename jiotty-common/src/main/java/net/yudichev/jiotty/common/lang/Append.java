package net.yudichev.jiotty.common.lang;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.Map;

@SuppressWarnings("OverloadedMethodsWithSameNumberOfParameters")
public final class Append {
    private Append() {
    }

    public static void to(Appendable to, CharSequence csq) {
        try {
            to.append(csq);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void to(Appendable to, CharSequence csq, int start, int end) {
        try {
            to.append(csq, start, end);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void to(Appendable to, char c) {
        try {
            to.append(c);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void to(Appendable to, boolean b) {
        switch (to) {
            case StringBuilder sb -> sb.append(b);
            default -> {
                try {
                    to.append(Boolean.toString(b));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    public static void to(Appendable to, int i) {
        switch (to) {
            case StringBuilder sb -> sb.append(i);
            default -> {
                try {
                    to.append(Integer.toString(i));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    public static void to(Appendable to, long l) {
        switch (to) {
            case StringBuilder sb -> sb.append(l);
            default -> {
                try {
                    to.append(Long.toString(l));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    public static void to(Appendable to, float f) {
        switch (to) {
            case StringBuilder sb -> sb.append(f);
            default -> {
                try {
                    to.append(Float.toString(f));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    public static void to(Appendable to, double d) {
        switch (to) {
            case StringBuilder sb -> sb.append(d);
            default -> {
                try {
                    to.append(Double.toString(d));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    /// Appends `object` by dispatching on its runtime type, for values whose type is not known at compile time — a generic field, a collection element, a map
    /// value. A nested [StringFormattable], [Iterable] or [Map] is appended by its own overload, straight into `to`.
    ///
    /// @param object the value to append; `null` appends the text `null`
    public static void to(Appendable to, @Nullable Object object) {
        switch (object) {
            case null -> to(to, "null");
            case StringFormattable formattable -> formattable.formatTo(to);
            case CharSequence csq -> to(to, csq);
            case Character c -> to(to, (char) c);
            case Boolean b -> to(to, (boolean) b);
            case Integer i -> to(to, (int) i);
            case Long l -> to(to, (long) l);
            case Float f -> to(to, (float) f);
            case Double d -> to(to, (double) d);
            case Iterable<?> iterable -> to(to, iterable);
            case Map<?, ?> map -> to(to, map);
            default -> appendToString(to, object);
        }
    }

    private static void appendToString(Appendable to, Object object) {
        try {
            to.append(String.valueOf(object));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /// Appends `formattable`'s formatted form, or the text `null` when `formattable` is itself `null`.
    public static void to(Appendable to, @Nullable StringFormattable formattable) {
        if (formattable == null) {
            to(to, "null");
        } else {
            formattable.formatTo(to);
        }
    }

    public static <T> void to(Appendable to,
                              Iterable<? extends T> iterable,
                              ThrowingBiConsumer<? super Appendable, ? super T, ? extends Exception> appendCode) {
        to(to, iterable, "[", ", ", "]", appendCode);
    }

    /// Joins `iterable` with caller-chosen `prefix`, `separator`, and `suffix` in place of the defaults (`[`, `, `, `]`). Pass empty `prefix`/`suffix` for an
    /// unbracketed join and a bare `,` for a tight separator.
    public static <T> void to(Appendable to,
                              Iterable<? extends T> iterable,
                              String prefix,
                              String separator,
                              String suffix,
                              ThrowingBiConsumer<? super Appendable, ? super T, ? extends Exception> appendCode) {
        int i = 0;
        to(to, prefix);
        for (T item : iterable) {
            if (i++ > 0) {
                to(to, separator);
            }
            try {
                appendCode.accept(to, item);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        to(to, suffix);
    }


    public static void to(Appendable to, Iterable<?> iterable) {
        to(to, iterable, Append::to);
    }

    public static <K, V> void to(Appendable to,
                                 Map<? extends K, ? extends V> map,
                                 ThrowingBiConsumer<? super Appendable, ? super K, ? extends Exception> keyAppendCode,
                                 ThrowingBiConsumer<? super Appendable, ? super V, ? extends Exception> valueAppendCode) {
        boolean notFirst = false;
        to(to, '{');
        // entrySet loop rather than Map.forEach: the append code may throw a checked exception, and the separator needs mutable per-iteration state
        for (Map.Entry<? extends K, ? extends V> entry : map.entrySet()) {
            if (notFirst) {
                to(to, ", ");
            }
            notFirst = true;
            try {
                keyAppendCode.accept(to, entry.getKey());
                to(to, '=');
                valueAppendCode.accept(to, entry.getValue());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        to(to, '}');
    }

    public static void to(Appendable to, Map<?, ?> map) {
        to(to, map, Append::to, Append::to);
    }
}

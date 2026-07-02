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

    public static void to(Appendable to, Object object) {
        switch (to) {
            case StringBuilder sb -> sb.append(object);
            default -> {
                try {
                    to.append(String.valueOf(object));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
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
        int i = 0;
        to(to, '[');
        for (T item : iterable) {
            if (i++ > 0) {
                to(to, ", ");
            }
            try {
                appendCode.accept(to, item);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        to(to, ']');
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

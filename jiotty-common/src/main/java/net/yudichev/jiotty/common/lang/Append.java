package net.yudichev.jiotty.common.lang;

import java.io.IOException;

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

    public static void to(Appendable to, StringFormattable formattable) {
        try {
            formattable.formatTo(to);
        } catch (IOException e) {
            throw new RuntimeException(e);
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
}

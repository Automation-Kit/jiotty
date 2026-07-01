package net.yudichev.jiotty.common.lang;

import org.apache.logging.log4j.util.StringBuilderFormattable;

import java.io.IOException;

@SuppressWarnings("OverloadedMethodsWithSameNumberOfParameters") // formatTo(StringBuilder) is the inherited log4j contract; formatTo(Appendable) generalises it
public interface StringFormattable extends StringBuilderFormattable {
    void formatTo(Appendable buffer) throws IOException;

    @Override
    default void formatTo(StringBuilder buffer) {
        try {
            formatTo((Appendable) buffer);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    default String toString(int bufferCapacity) {
        StringBuilder sb = new StringBuilder(bufferCapacity);
        formatTo(sb);
        return sb.toString();
    }
}

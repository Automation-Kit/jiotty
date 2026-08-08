package net.yudichev.jiotty.common.lang;

import org.apache.logging.log4j.util.StringBuilderFormattable;

/// A value that writes its string form straight into an [Appendable], so composite values and log layouts assemble output in a single shared buffer.
/// Extends log4j's [StringBuilderFormattable]: a value passed as a `{}` log argument is formatted lazily, straight into the log line's buffer.
@SuppressWarnings("OverloadedMethodsWithSameNumberOfParameters") // formatTo(StringBuilder) is the inherited log4j contract; formatTo(Appendable) generalises it
public interface StringFormattable extends StringBuilderFormattable {
    /// Writes this value's string form to `appendable`.
    ///
    /// @implSpec append via the [Append] helpers rather than [Appendable#append(CharSequence)], so that checked I/O failures surface as unchecked
    /// exceptions and implementations need no `throws` clause
    void formatTo(Appendable appendable);

    @Override
    default void formatTo(StringBuilder buffer) {
        formatTo((Appendable) buffer);
    }

    /// Materialises [#formatTo(Appendable)] into a [String] — the conventional body of an implementor's [Object#toString()] override.
    ///
    /// @param bufferCapacity initial buffer size; pick a value that comfortably fits the formatted form
    default String toString(int bufferCapacity) {
        var sb = new StringBuilder(bufferCapacity);
        formatTo(sb);
        return sb.toString();
    }
}

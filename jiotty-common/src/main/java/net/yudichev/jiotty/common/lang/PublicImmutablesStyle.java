package net.yudichev.jiotty.common.lang;

import org.immutables.value.Value;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.PACKAGE;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.CLASS;

@Target({PACKAGE, TYPE})
@Retention(CLASS) // Make it class retention for incremental compilation
// Detect 'get' and 'is' prefixes in accessor methods
// Builder initialization methods will have 'set' prefix
// 'Abstract' prefix will be detected and trimmed
// No prefix or suffix for generated immutable type
@Value.Style(get = {"is*", "get*"},
        init = "set*",
        typeAbstract = "Base*",
        typeImmutable = "*",
        visibility = Value.Style.ImplementationVisibility.PUBLIC,
        redactedMask = "…")
public @interface PublicImmutablesStyle {
}

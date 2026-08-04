package net.yudichev.jiotty.common.async;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.CLASS;

/// Documents that the annotated method — or every method of the annotated type — is always called on the thread of a named executor.
///
/// This annotation is documentation: nothing verifies it at runtime.
///
/// @see TaskExecutor#execute(String, Runnable)
@Documented
@Target({METHOD, TYPE})
@Retention(CLASS)
public @interface RunsOn {
    /// The executor whose thread runs this code, named as a reader would recognise it — the executor's thread name, or the field or binding it is injected as
    /// when the thread name is not fixed, or anything else.
    String executor();

    /// What makes the guarantee hold: the call path that reaches this code already on that executor's thread. Name the caller, not the conclusion — e.g.
    /// "this method is called by component X, which runs on this executor".
    String guaranteedBy();
}

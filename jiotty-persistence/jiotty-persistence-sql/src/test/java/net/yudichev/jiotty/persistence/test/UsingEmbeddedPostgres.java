package net.yudichev.jiotty.persistence.test;

import org.junit.jupiter.api.parallel.ResourceLock;

import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/// Declares that a test class runs its own [EmbeddedPostgresExtension], so no two such classes execute concurrently. Each one is a real postmaster with its
/// own shared buffers and file handles, and bounding how many exist at once keeps a parallel suite within the machine's memory and descriptor budget.
@Retention(RUNTIME)
@Target(TYPE)
@Inherited
@ResourceLock("net.yudichev.jiotty.persistence.test.EmbeddedPostgres")
public @interface UsingEmbeddedPostgres {
}

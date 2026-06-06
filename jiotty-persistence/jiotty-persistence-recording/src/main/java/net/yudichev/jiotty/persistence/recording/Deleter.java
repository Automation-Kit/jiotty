package net.yudichev.jiotty.persistence.recording;

import java.util.concurrent.CompletableFuture;

/// The delete counterpart of [Reader]: executes a templated statement against this destination's recorder table. The template may reference
/// `%TABLE_NAME%` (the recorder table) and `%USER_CONDITION%` (the predicate selecting the rows of the user this destination is scoped to). Completes with the
/// number of rows affected.
public interface Deleter {
    CompletableFuture<Integer> delete(String deleteTemplate);
}

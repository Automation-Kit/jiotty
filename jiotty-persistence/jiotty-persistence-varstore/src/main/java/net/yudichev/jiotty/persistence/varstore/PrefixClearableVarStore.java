package net.yudichev.jiotty.persistence.varstore;

import java.util.List;

/// A [VarStore] backed by a flat key-space that scopes users by a key prefix (file-backed and in-memory stores). [UserScopedVarStore] uses
/// [#clearAllWithPrefix] to wipe a single user's keys, and [#exportEntriesWithPrefix] to export them, without enumerating them through the public interface.
interface PrefixClearableVarStore extends VarStore {
    void clearAllWithPrefix(String keyPrefix);

    /// Returns the [export entries][ExportedEntry] for every key starting with `keyPrefix`, with the prefix stripped from each returned key.
    List<ExportedEntry> exportEntriesWithPrefix(String keyPrefix);
}

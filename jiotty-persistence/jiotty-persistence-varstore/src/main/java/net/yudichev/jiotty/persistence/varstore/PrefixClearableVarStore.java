package net.yudichev.jiotty.persistence.varstore;

import java.util.List;
import java.util.Set;

/// A [VarStore] backed by a flat key-space that scopes users by a key prefix (file-backed and in-memory stores). [UserScopedVarStore] uses
/// [#clearAllWithPrefix], [#exportEntriesWithPrefix] and [#keysWithPrefix] to wipe, export and list a single user's keys.
interface PrefixClearableVarStore extends VarStore {
    void clearAllWithPrefix(String keyPrefix);

    /// Returns the [export entries][ExportedEntry] for every key starting with `keyPrefix`, with the prefix stripped from each returned key.
    List<ExportedEntry> exportEntriesWithPrefix(String keyPrefix);

    /// Returns every key starting with `keyPrefix` followed by `prefix`, with `keyPrefix` stripped from each returned key.
    Set<String> keysWithPrefix(String keyPrefix, String prefix);
}

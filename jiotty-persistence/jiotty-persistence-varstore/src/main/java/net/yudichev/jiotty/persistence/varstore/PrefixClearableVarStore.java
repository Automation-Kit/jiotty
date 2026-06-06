package net.yudichev.jiotty.persistence.varstore;

/// A [VarStore] backed by a flat key-space that scopes users by a key prefix (file-backed and in-memory stores). [UserScopedVarStore] uses
/// [#clearAllWithPrefix] to wipe a single user's keys without enumerating them through the public interface.
interface PrefixClearableVarStore extends VarStore {
    void clearAllWithPrefix(String keyPrefix);
}

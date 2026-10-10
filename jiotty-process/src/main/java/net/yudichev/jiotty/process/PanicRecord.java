package net.yudichev.jiotty.process;

import java.util.Optional;

/// The reason the process last stopped on a panic, kept across the restart.
public interface PanicRecord {
    /// Records nothing and reads nothing back.
    PanicRecord NONE = new PanicRecord() {
        @Override
        public void write(String reason) {
        }

        @Override
        public Optional<String> read() {
            return Optional.empty();
        }

        @Override
        public void clear() {
        }
    };

    /// Records `reason` durably before returning.
    void write(String reason);

    /// @return the reason recorded by an earlier process that stopped on a panic and has not been [cleared](#clear())
    Optional<String> read();

    /// Forgets the recorded reason.
    void clear();
}

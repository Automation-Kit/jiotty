package net.yudichev.jiotty.persistence.recording;

import java.time.Instant;

public interface Recorder<R> {
    void record(Instant timestamp, R recordable);

    void record(DestinationType destinationType, Instant timestamp, R recordable);
}

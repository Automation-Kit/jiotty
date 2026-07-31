package net.yudichev.jiotty.common.misc;

import com.google.common.collect.ImmutableList;

import java.util.ArrayList;
import java.util.List;

/// Records what a component reports about its upstream's health, so tests can assert which failure kinds count as an outage.
public final class RecordingUpstreamHealthHandler implements UpstreamHealthHandler {
    private final List<String> failures = new ArrayList<>();
    private int successCount;

    @Override
    public void onFailure(String message, Throwable cause) {
        failures.add(message + ": " + cause);
    }

    @Override
    public void onSuccess() {
        successCount++;
    }

    /// Each recorded failure as `message + ": " + cause`, oldest first.
    public List<String> failures() {
        return ImmutableList.copyOf(failures);
    }

    public int successCount() {
        return successCount;
    }
}

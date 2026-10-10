package net.yudichev.jiotty.process;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FilePanicRecordTest {
    @TempDir
    private Path tempDir;

    @Test
    void readsNothingBeforeAPanicIsRecorded() {
        assertThat(new FilePanicRecord(tempDir.resolve("panic-reason")).read()).isEmpty();
    }

    @Test
    void anotherProcessReadsTheRecordedReasonUntilItIsCleared() {
        Path file = tempDir.resolve("panic-reason");
        new FilePanicRecord(file).write("executor UserManagement has a full queue");

        var restartedProcessRecord = new FilePanicRecord(file);
        assertThat(restartedProcessRecord.read()).contains("executor UserManagement has a full queue");

        restartedProcessRecord.clear();
        assertThat(restartedProcessRecord.read()).isEmpty();
        assertThat(file).doesNotExist();
    }

    @Test
    void clearingWithNothingRecordedIsHarmless() {
        var panicRecord = new FilePanicRecord(tempDir.resolve("panic-reason"));

        panicRecord.clear();

        assertThat(panicRecord.read()).isEmpty();
    }
}

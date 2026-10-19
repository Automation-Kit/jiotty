package net.yudichev.jiotty.user.ui.io;

import static com.google.common.base.Preconditions.checkNotNull;

/// The reason a [ContainerOutput]'s exchange ended.
public sealed interface EndReason {
    /// The source finished, and every body it returned was written.
    final class Finished implements EndReason {
        public static final Finished INSTANCE = new Finished();

        private Finished() {
        }
    }

    /// The owner closed the output.
    final class Closed implements EndReason {
        public static final Closed INSTANCE = new Closed();

        private Closed() {
        }
    }

    /// The exchange failed: the client went away, stopped reading for longer than the container's idle timeout, or the container ended it.
    record Failed(Throwable cause) implements EndReason {
        public Failed {
            checkNotNull(cause, "cause");
        }
    }

    /// A body could not be encoded, which is a defect in the data it carries. An answer not yet committed is sent as a bare 500.
    record EncodingFailed(Exception cause) implements EndReason {
        public EncodingFailed {
            checkNotNull(cause, "cause");
        }
    }
}

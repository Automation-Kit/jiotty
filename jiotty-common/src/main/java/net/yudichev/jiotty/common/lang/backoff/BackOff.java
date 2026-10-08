/*
 * Copyright (c) 2011 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package net.yudichev.jiotty.common.lang.backoff;

/// Back-off policy when retrying an operation.
///
/// @author Ravi Mistry
public interface BackOff {

    /// Indicates that no more retries should be made for use in [#nextBackOffMillis()].
    long STOP = -1L;

    /// Reset to initial state.
    void reset();

    /// Gets the number of milliseconds to wait before retrying the operation or [#STOP] to indicate that no retries should be made.
    ///
    /// Example usage:
    /// <pre>
    /// long backOffMillis = backoff.nextBackOffMillis();
    /// if (backOffMillis == Backoff.STOP) {
    /// // do not retry operation
    /// } else {
    /// // sleep for backOffMillis milliseconds and retry operation
    /// }
    /// </pre>
    long nextBackOffMillis();

    /// Returns the maximum elapsed time in milliseconds.
    ///
    /// If the time elapsed since the first [#nextBackOffMillis()] after creation or [reset][#reset()] goes past this value then [#nextBackOffMillis()] starts
    /// returning [BackOff#STOP].
    long getMaxElapsedTimeMillis();
}

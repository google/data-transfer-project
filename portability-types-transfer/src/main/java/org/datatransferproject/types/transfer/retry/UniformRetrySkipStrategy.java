/*
 * Copyright 2024 The Data Transfer Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.datatransferproject.types.transfer.retry;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;

/**
 * {@link RetryStrategy} that follows a regular retry strategy that allows exception to be skipped.
 */
public class UniformRetrySkipStrategy implements RetryStrategy {

  @JsonProperty("maxAttempts")
  private int maxAttempts;
  @JsonProperty("intervalMillis")
  private long intervalMillis;
  @JsonProperty("identifier")
  private String identifier;

  public UniformRetrySkipStrategy(
     @JsonProperty("maxAttempts") int maxAttempts,
      @JsonProperty("intervalMillis") long intervalMillis,
      @JsonProperty("identifier") String identifier) {
    Preconditions.checkArgument(maxAttempts > 0, "Max attempts should be > 0");
    Preconditions.checkArgument(intervalMillis > 0L, "Interval should be > 0");
    // TODO: enforce stronger requirements (e.g., interval > 500ms)
    this.maxAttempts = maxAttempts;
    this.intervalMillis = intervalMillis;
    this.identifier = identifier;
  }

  @Override
  public boolean canTryAgain(int tries) {
    return tries <= maxAttempts;
  }

  @Override
  public long getNextIntervalMillis(int tries) {
    return intervalMillis;
  }

  @Override
  public long getRemainingIntervalMillis(int tries, long elapsedMillis) {
    Preconditions.checkArgument(tries <= maxAttempts, "No retries left");
    return intervalMillis - elapsedMillis;
  }

  @Override
  public boolean canSkip() {
    return true;
  }

  @Override
  public String toString() {
    return "UniformRetrySkipStrategy{" +
        "maxAttempts=" + maxAttempts +
        ", intervalMillis=" + intervalMillis +
        ", identifier=" + identifier +
        '}';
  }
}

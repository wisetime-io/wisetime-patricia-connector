/*
 * Copyright (c) 2017 Practice Insight Pty Ltd. All rights reserved.
 */

package io.wisetime.connector.patricia.posting_time;

import org.immutables.value.Value;

import java.math.BigDecimal;

import javax.annotation.Nullable;

/**
 * This encapsulates Patricia's time record discount information which is used by agent to
 * calculate discount percent and amount for time record.
 *
 * @author paul.labis@practiceinsight.io
 */
@Value.Immutable
public interface PatriciaDiscountRecord {

  @Nullable
  Integer caseTypeId();

  @Nullable
  String stateId();

  @Nullable
  Integer applicationTypeId();

  @Nullable
  String workCodeId();

  @Nullable
  String workCodeType();

  @Value.Default
  default int priority() {
    return 0;
  }

  @Value.Default
  default int discountId() {
    return 0;
  }

  @Value.Default
  default int discountType() {
    return 0;
  }

  @Value.Default
  default BigDecimal amount() {
    return BigDecimal.ZERO;
  }

  @Value.Default
  default BigDecimal discountPercent() {
    return BigDecimal.ZERO;
  }
}

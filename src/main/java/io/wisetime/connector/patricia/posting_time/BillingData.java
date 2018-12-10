/*
 * Copyright (c) 2017 Practice Insight Pty Ltd. All rights reserved.
 */

package io.wisetime.connector.patricia.posting_time;

import org.immutables.value.Value;

import java.math.BigDecimal;

/**
 * @author alvin.llobrera@practiceinsight.io
 */
@Value.Immutable
public interface BillingData {

  String currency();

  BigDecimal hourlyRate();

  WorkedHoursComputation actualWork();

  WorkedHoursComputation chargeableWork();

  WorkedHoursComputation actualWorkWithExperienceFactor();

  WorkedHoursComputation chargeableWorkWithExperienceFactor();
}

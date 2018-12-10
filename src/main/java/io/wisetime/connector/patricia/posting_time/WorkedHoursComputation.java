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
public interface WorkedHoursComputation {

  BigDecimal totalHours();

  BigDecimal totalAmount();

  BigDecimal discountAmount();

  BigDecimal discountPercentage();

  BigDecimal discountedHourlyRate();
}

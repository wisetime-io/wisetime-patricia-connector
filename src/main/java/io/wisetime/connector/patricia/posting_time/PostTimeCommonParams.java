/*
 * Copyright (c) 2017 Practice Insight Pty Ltd. All rights reserved.
 */

package io.wisetime.connector.patricia.posting_time;

import org.immutables.value.Value;

/**
 * @author alvin.llobrera@practiceinsight.io
 */
@Value.Immutable
public interface PostTimeCommonParams {

  Integer caseId();

  String caseName();

  String workCodeId();

  String recordalDate();

  String loginId();

  float experienceWeightingPercent();
}

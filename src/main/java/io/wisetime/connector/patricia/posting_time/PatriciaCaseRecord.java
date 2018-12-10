/*
 * Copyright (c) 2017 Practice Insight Pty Ltd. All rights reserved.
 */

package io.wisetime.connector.patricia.posting_time;

import org.immutables.value.Value;

/**
 * This maps some fields from PAT_CASE table in Patricia DB.
 * It holds relevant data for a "CASE", which are needed for calculating discount priority.
 *
 * @author alvin.llobrera@practiceinsight.io
 */
@Value.Immutable
public interface PatriciaCaseRecord {

  Integer caseId();

  Integer caseTypeId();

  String stateId();

  Integer appId();
}

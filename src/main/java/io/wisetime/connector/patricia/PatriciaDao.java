/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.patricia;

import com.google.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.codejargon.fluentjdbc.api.FluentJdbc;
import org.codejargon.fluentjdbc.api.FluentJdbcBuilder;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

import javax.sql.DataSource;

import io.wisetime.generated.connect.UpsertTagRequest;

/**
 * Simple, unsophisticated access to the Patricia database.
 *
 * @author vadym
 */
class PatriciaDao {

  private final Logger log = LoggerFactory.getLogger(PatriciaDao.class);
  private final FluentJdbc fluentJdbc;

  @Inject
  PatriciaDao(DataSource dataSource) {
    fluentJdbc = new FluentJdbcBuilder().connectionProvider(dataSource).build();
  }

  boolean isHealthy() {
    return true;
  }

  List<PatriciaCase> findCasesOrderById(long startIdExclusive, int maxResults) {
    return Collections.emptyList();
  }

  @Value.Immutable
  public interface PatriciaCase {

    long getId();

    String caseNumber();

    String caseCatchWord();

    default UpsertTagRequest toUpsertTagRequest(String path) {
      UpsertTagRequest upsertTagRequest = new UpsertTagRequest();
      upsertTagRequest.path(path);
      upsertTagRequest.name(caseNumber());
      upsertTagRequest.setDescription(StringUtils.trimToEmpty(caseCatchWord()));
      return upsertTagRequest;
    }
  }
}

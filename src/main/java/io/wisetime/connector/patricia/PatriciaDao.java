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

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.sql.DataSource;

import io.wisetime.connector.patricia.posting_time.BillingData;
import io.wisetime.connector.patricia.posting_time.PatriciaCaseRecord;
import io.wisetime.connector.patricia.posting_time.PatriciaDiscountRecord;
import io.wisetime.connector.patricia.posting_time.PostTimeCommonParams;
import io.wisetime.generated.connect.Tag;
import io.wisetime.generated.connect.UpsertTagRequest;

/**
 * Simple, unsophisticated access to the Patricia database.
 *
 * @author vadym
 */
public class PatriciaDao {

  private final Logger log = LoggerFactory.getLogger(PatriciaDao.class);
  private final FluentJdbc fluentJdbc;

  @Inject
  PatriciaDao(DataSource dataSource) {
    fluentJdbc = new FluentJdbcBuilder().connectionProvider(dataSource).build();
  }

  public void asTransaction(final Runnable runnable) {
    fluentJdbc.query().transaction().inNoResult(runnable);
  }

  boolean isHealthy() {
    return getDbDate().isPresent();
  }

  List<PatriciaCase> findCasesOrderById(final long startIdExclusive, final int maxResults) {
    return Collections.emptyList();
  }

  Optional<String> findLoginByEmail(final String email) {
    return Optional.empty();
  }

  public Map<Integer, Tag> findCaseIds(final List<Tag> tags) {
    return Collections.emptyMap();
  }

  public void updateBudgetHeader(final int caseId) {

  }

  public Optional<String> findCurrency(final int caseId) {
    return Optional.empty();
  }

  public Optional<BigDecimal> findUserHourlyRate(final String workCodeId, final String loginId) {
    return Optional.empty();
  }

  public List<PatriciaDiscountRecord> findDiscountRecords(final String workCodeId, final int caseId) {
    return Collections.emptyList();
  }

  public Optional<PatriciaCaseRecord> findPatCaseData(final int caseId) {
    return Optional.empty();
  }

  public void addTimeRegistration(final PostTimeCommonParams commonParams,
                                  final BillingData billingData, final String comment) {

  }

  public void addBudgetLine(final PostTimeCommonParams commonParams, final BillingData billingData, final String comment) {

  }

  public Optional<String> getDbDate() {
    return Optional.empty();
  }

  @Value.Immutable
  public interface PatriciaCase {

    long getId();

    String caseNumber();

    String caseCatchWord();

    default UpsertTagRequest toUpsertTagRequest(final String path) {
      final UpsertTagRequest upsertTagRequest = new UpsertTagRequest();
      upsertTagRequest.path(path);
      upsertTagRequest.name(caseNumber());
      upsertTagRequest.setDescription(StringUtils.trimToEmpty(caseCatchWord()));
      return upsertTagRequest;
    }
  }
}

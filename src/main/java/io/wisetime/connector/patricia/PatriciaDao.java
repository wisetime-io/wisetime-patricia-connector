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
import java.util.Optional;

import javax.annotation.Nullable;
import javax.sql.DataSource;

import io.wisetime.generated.connect.UpsertTagRequest;

/**
 * Simple, unsophisticated access to the Patricia database.
 *
 * @author vadym
 */
public class PatriciaDao {

  private final Logger log = LoggerFactory.getLogger(PatriciaDao.class);
  static final int PURE_DISCOUNT = 1;
  static final int MARK_UP_DISCOUNT = 2;
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

  List<Case> findCasesOrderById(final long startIdExclusive, final int maxResults) {
    // TODO: Implement
    return Collections.emptyList();
  }

  Optional<String> findLoginByEmail(final String email) {
    // TODO: Implement
    return Optional.empty();
  }

  public void updateBudgetHeader(final long caseId) {
    // TODO: Implement
  }

  public Optional<String> findCurrency(final long caseId) {
    return Optional.empty();
  }

  public Optional<BigDecimal> findUserHourlyRate(final String workCodeId, final String loginId) {
    // TODO: Implement
    return Optional.empty();
  }

  public List<Discount> findDiscounts(final String workCodeId, final long caseId) {
    // TODO: Implement
    return Collections.emptyList();
  }

  public Optional<Case> findCaseById(final long caseId) {
    // TODO: Implement
    return Optional.empty();
  }

  public Optional<Case> findCaseByTagName(final String tagName) {
    // TODO: Implement
    return Optional.empty();
  }

  public void addTimeRegistration(TimeRegistration timeRegistration) {
    // TODO: Implement
  }

  public void addBudgetLine(BudgetLine budgetLine) {
    // TODO: Implement
  }

  public Optional<String> getDbDate() {
    // TODO: Implement
    return Optional.empty();
  }

  /**
   * This maps some fields from pat_case and vw_case_number tables in Patricia DB.
   * It holds relevant data for a case, which are needed for creating tag and calculating discount priority.
   */
  @Value.Immutable
  public interface Case {

    long caseId();

    String caseNumber();

    String caseCatchWord();

    Integer caseTypeId();

    String stateId();

    Integer appId();

    default UpsertTagRequest toUpsertTagRequest(final String path) {
      final UpsertTagRequest upsertTagRequest = new UpsertTagRequest();
      upsertTagRequest.path(path);
      upsertTagRequest.name(caseNumber());
      upsertTagRequest.setDescription(StringUtils.trimToEmpty(caseCatchWord()));
      return upsertTagRequest;
    }
  }

  /**
   * This encapsulates Patricia's time record discount information which is used by agent to
   * calculate discount percent and amount for time record.
   */
  @Value.Immutable
  public interface Discount {

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

  @Value.Immutable
  public interface TimeRegistration {
    long caseId();

    String workCodeId();

    String userId();

    String recordalDate();

    BigDecimal actualHours();

    BigDecimal chargeableHours();

    String comment();
  }

  @Value.Immutable
  public interface BudgetLine {

    long caseId();

    String workCodeId();

    String userId();

    String recordalDate();

    String currency();

    BigDecimal hourlyRate();

    BigDecimal actualWorkTotalHours();

    BigDecimal chargeableWorkTotalHours();

    BigDecimal chargeAmount();

    BigDecimal discountPercentage();

    BigDecimal discountAmount();

    BigDecimal effectiveHourlyRate();

    String comment();
  }

  @Value.Immutable
  public interface CreateTimeAndChargeParams {

    Case patriciaCase();

    String workCode();

    String userId();

    String timeRegComment();

    String chargeComment();

    BigDecimal hourlyRate();

    BigDecimal workedHoursWithExpRating();

    BigDecimal workedHoursWithoutExpRating();
  }
}

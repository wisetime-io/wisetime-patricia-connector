/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.patricia;

import com.google.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.codejargon.fluentjdbc.api.FluentJdbc;
import org.codejargon.fluentjdbc.api.FluentJdbcBuilder;
import org.codejargon.fluentjdbc.api.mapper.Mappers;
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
  private final FluentJdbc fluentJdbc;

  @Inject
  PatriciaDao(DataSource dataSource) {
    fluentJdbc = new FluentJdbcBuilder().connectionProvider(dataSource).build();
  }

  void asTransaction(final Runnable runnable) {
    fluentJdbc.query().transaction().inNoResult(runnable);
  }

  boolean isHealthy() {
    return getDbDate().isPresent();
  }

  List<Case> findCasesOrderById(final long startIdExclusive, final int maxResults) {
    return fluentJdbc.query().select("SELECT TOP ? vcn.case_id, vcn.case_number, pc.case_catch_word, " +
        " pc.case_type_id, pc.state_id, pc.application_type_id " +
        " FROM vw_case_number vcn JOIN pat_case pc ON vcn.case_id = pc.case_id " +
        " WHERE vcn.case_id > ? ORDER BY vcn.case_id ASC")
        .params(maxResults, startIdExclusive)
        .listResult(rs ->
          ImmutableCase.builder()
              .caseId(rs.getLong(1))
              .caseNumber(rs.getString(2))
              .caseCatchWord(rs.getString(3))
              .caseTypeId(rs.getInt(4))
              .stateId(rs.getString(5))
              .appId(rs.getInt(6))
              .build()
        );
  }

  Optional<String> findLoginByEmail(final String email) {
    return fluentJdbc.query().select("SELECT login_id FROM person WHERE LOWER(email) = ?")
        .params(email.toLowerCase())
        .firstResult(Mappers.singleString());
  }

  Optional<String> findCurrency(final long caseId, final int roleTypeId) {
    return fluentJdbc.query().select(
        "SELECT currency_id FROM pat_names WHERE name_id = " +
            "(SELECT DISTINCT actor_id FROM casting WHERE case_id = ? AND role_type_id = ? AND case_role_sequence = 1)")
        .params(caseId, roleTypeId)
        .firstResult(Mappers.singleString());
  }

  public Optional<BigDecimal> findUserHourlyRate(final String workCodeId, final String loginId) {
    // TODO: Implement
    return Optional.empty();
  }

  public List<Discount> findDiscounts(final String workCodeId, final long caseId) {
    // TODO: Implement
    return Collections.emptyList();
  }

  public Optional<Case> findCaseByTagName(final String tagName) {
    // TODO: Implement
    return Optional.empty();
  }

  public void updateBudgetHeader(final long caseId) {
    // TODO: Implement
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

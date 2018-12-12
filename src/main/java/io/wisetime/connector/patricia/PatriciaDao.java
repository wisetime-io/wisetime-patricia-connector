/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.patricia;

import com.google.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.codejargon.fluentjdbc.api.FluentJdbc;
import org.codejargon.fluentjdbc.api.FluentJdbcBuilder;
import org.codejargon.fluentjdbc.api.mapper.Mappers;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
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
        .listResult(this::mapToCase);
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

  Optional<BigDecimal> findUserHourlyRate(final String workCodeId, final String loginId) {
    Optional<BigDecimal> hourlyRate = fluentJdbc.query().select(
        "  SELECT CASE WHEN EXISTS (" +
             "    SELECT pat_person_hourly_rate_id" +
             "    FROM pat_person_hourly_rate pphr" +
             "      WHERE pphr.login_id = :login_id AND pphr.work_code_id = :wc_id)" +
             "  THEN (" +
             "    SELECT pphr.hourly_rate" +
             "    FROM pat_person_hourly_rate pphr" +
             "      WHERE pphr.login_id = :login_id AND pphr.work_code_id = :wc_id)" +
             "  ELSE (" +
             "    SELECT person.hourly_rate" +
             "    FROM person" +
             "    WHERE person.login_id = :login_id)" +
             "  END"
    )
        .namedParam("login_id", loginId)
        .namedParam("wc_id", workCodeId)
        .firstResult(rs ->
            rs.getBigDecimal(1) != null ? rs.getBigDecimal(1) : BigDecimal.ZERO
        );

    if (hourlyRate.isPresent() && hourlyRate.get().compareTo(BigDecimal.ZERO) > 0) {
      return hourlyRate;
    } else {
      // unit price = 0 means there is no unit price retrieved in DB
      return Optional.empty();
    }
  }

  List<Discount> findDiscounts(final String workCodeId, final int roleTypeId, final long caseId) {
    return fluentJdbc.query().select(
        "SELECT "
            + "wcdh.discount_id, "
            + "wcdh.case_type_id, "
            + "wcdh.state_id, "
            + "wcdh.application_type_id, "
            + "wcdh.work_code_type, "
            + "wcdh.work_code_id, "
            + "wcdh.discount_type, "
            + "wcdd.amount, "
            + "wcdd.discount_pct "
            + "FROM pat_work_code_discount_header wcdh "
            + "JOIN pat_work_code_discount_detail wcdd ON wcdh.discount_id = wcdd.discount_id "
            + "JOIN casting ON wcdh.actor_id = casting.actor_id "
            + "WHERE casting.case_id = :case_id "
            + "AND casting.role_type_id = :role_type_id "
            + "AND (wcdh.work_code_type IS NULL OR wcdh.work_code_type = 'T') "
            + "AND (wcdh.work_code_id IS NULL OR wcdh.work_code_id = :work_code_id)")
        .namedParam("case_id", caseId)
        .namedParam("role_type_id", roleTypeId)
        .namedParam("work_code_id", workCodeId)
        .listResult(this::mapDiscountRecord);  // returns an immutable list
  }

  Optional<Case> findCaseByTagName(final String tagName) {
    return fluentJdbc.query().select("SELECT vcn.case_id, vcn.case_number, pc.case_catch_word, " +
        " pc.case_type_id, pc.state_id, pc.application_type_id " +
        " FROM vw_case_number vcn JOIN pat_case pc ON vcn.case_id = pc.case_id " +
        " WHERE vcn.case_number = ?")
        .params(tagName)
        .firstResult(this::mapToCase);
  }

  void updateBudgetHeader(final long caseId, final String recordalDate) {
    final boolean budgetHeaderExist =
        fluentJdbc.query().select("SELECT COUNT(*) FROM budget_header WHERE case_id = ?")
            .params(caseId)
            .singleResult(Mappers.singleLong()) > 0;

    if (budgetHeaderExist) {
      fluentJdbc.query().update("UPDATE budget_header SET budget_edit_date = ? WHERE case_id = ?")
          .params(recordalDate, caseId)
          .run();
    } else {
      fluentJdbc.query().update("INSERT INTO budget_header (case_id, budget_edit_date) VALUES (?, ?)")
          .params(caseId, recordalDate)
          .run();
    }
  }

  void addTimeRegistration(TimeRegistration timeRegistration) {
    fluentJdbc.query().update(
        "INSERT INTO time_registration ("
            + "  work_code_id,"
            + "  case_id,"
            + "  registration_date_time,"
            + "  login_id,"
            + "  calendar_date,"
            + "  worked_time,"
            + "  debited_time,"
            + "  time_transferred,"
            + "  number_of_words,"
            + "  worked_amount,"
            + "  b_l_case_id,"
            + "  time_comment_invoice,"
            + "  time_comment,"
            + "  time_reg_booked_date,"
            + "  earliest_invoice_date"
            + ")"
            + "VALUES ("
            + "  :wc, :cid, :rd, :li, :cd, :wt, :dt, :tt, :nw, :wa, :bci, :tci, :tc, :bd, :eid"
            + ")"
    )
        .namedParam("wc", timeRegistration.workCodeId())
        .namedParam("cid", timeRegistration.caseId())
        .namedParam("rd", timeRegistration.recordalDate())
        .namedParam("li", timeRegistration.userId())
        .namedParam("cd", timeRegistration.recordalDate())
        .namedParam("wt", timeRegistration.actualHours())
        .namedParam("dt", timeRegistration.chargeableHours())
        .namedParam("tt", "!")
        .namedParam("nw", 0)
        .namedParam("wa", "0.00")
        .namedParam("bci", timeRegistration.caseId())
        .namedParam("tci", timeRegistration.comment())
        .namedParam("tc", timeRegistration.comment())
        .namedParam("bd", timeRegistration.recordalDate())
        .namedParam("eid", timeRegistration.recordalDate())
        .run();
  }

  void addBudgetLine(BudgetLine budgetLine) {
    fluentJdbc.query().update(
        "INSERT INTO budget_line ("
            + "  b_l_seq_number,"
            + "  work_code_id,"
            + "  b_l_quantity,"
            + "  b_l_org_quantity,"
            + "  b_l_unit_price,"
            + "  b_l_org_unit_price,"
            + "  b_l_unit_price_no_discount,"
            + "  deb_handlagg,"
            + "  b_l_amount,"
            + "  b_l_org_amount,"
            + "  case_id,"
            + "  show_time_comment,"
            + "  registered_by,"
            + "  earliest_inv_date,"
            + "  b_l_comment,"
            + "  recorded_date,"
            + "  discount_prec,"
            + "  discount_amount,"
            + "  currency_id,"
            + "  exchange_rate"
            + ")"
            + "VALUES ("
            + "  :bsn, :wc, :dt, :wt, :upd, :upd, :up, :li, :ttlblamt, :ttlbloamt, :cid, "
            + "  :stc, :li, :eid, :tci, :rd, :discperc, :discamt, :cur, :er"
            + ")"
    )
        .namedParam("bsn", findNextBudgetLineSeqNum(budgetLine.caseId()))
        .namedParam("wc", budgetLine.workCodeId())
        .namedParam("dt", budgetLine.chargeableWorkTotalHours())
        .namedParam("wt", budgetLine.actualWorkTotalHours())
        .namedParam("upd", budgetLine.effectiveHourlyRate())
        .namedParam("up", budgetLine.hourlyRate())
        .namedParam("li", budgetLine.userId())
        .namedParam("ttlblamt", budgetLine.chargeableAmount())
        .namedParam("ttlbloamt", budgetLine.actualWorkTotalAmount())
        .namedParam("cid", budgetLine.caseId())
        .namedParam("stc", 1)
        .namedParam("eid", budgetLine.recordalDate())
        .namedParam("tci", budgetLine.comment())
        .namedParam("rd", budgetLine.recordalDate())
        .namedParam("discperc", budgetLine.discountPercentage())
        .namedParam("discamt", budgetLine.discountAmount())
        .namedParam("cur", budgetLine.currency())
        .namedParam("er", 1)
        .run();
  }

  Optional<String> getDbDate() {
    return fluentJdbc.query().select("SELECT getdate()").firstResult(Mappers.singleString());
  }

  int findNextBudgetLineSeqNum(long caseId) {
    return fluentJdbc.query()
        .select("SELECT MAX(b_l_seq_number)+1 FROM budget_line bl WHERE bl.case_id = ?")
        .params(caseId)
        .firstResult(rs -> NumberUtils.toInt(rs.getString(1), 1))
        .orElse(1);

  }

  private ImmutableCase mapToCase(ResultSet rs) throws SQLException {
    return ImmutableCase.builder()
        .caseId(rs.getLong(1))
        .caseNumber(rs.getString(2))
        .caseCatchWord(rs.getString(3))
        .caseTypeId(rs.getInt(4))
        .stateId(rs.getString(5))
        .appId(rs.getInt(6))
        .build();
  }

  private Discount mapDiscountRecord(ResultSet rset)
      throws SQLException {
    final int discountId = rset.getInt(1);
    final int caseTypeId = rset.getInt(2);
    final String stateId = rset.getString(3);
    final int applicationTypeId = rset.getInt(4);
    final String workCodeType = rset.getString(5);
    final String workCodeId = rset.getString(6);
    final int discountType = rset.getInt(7);
    final BigDecimal amount = rset.getBigDecimal(8);
    final BigDecimal discountPercent = rset.getBigDecimal(9);
    final DiscountPriority discountPriority = determineDiscountPriority(
        caseTypeId, stateId, applicationTypeId, workCodeId, workCodeType
    );

    return ImmutableDiscount.builder()
        .discountId(discountId)
        .caseTypeId(caseTypeId > 0 ? caseTypeId : null)
        .stateId(stateId)
        .applicationTypeId(applicationTypeId > 0 ? applicationTypeId : null)
        .workCodeType(workCodeType)
        .workCodeId(workCodeId)
        .amount(amount != null ? amount : BigDecimal.ZERO)
        .discountPercent(discountPercent != null ? discountPercent : BigDecimal.ZERO)
        .discountType(discountType)
        .priority(discountPriority.getPriorityNum())
        .build();
  }

  private DiscountPriority determineDiscountPriority(Integer caseTypeId,
                                                     String stateId,
                                                     Integer appTypeId,
                                                     String workCodeId,
                                                     String workCodeType) {
    return DiscountPriority.findDiscountPriority(
        caseTypeId > 0,
        StringUtils.isNotBlank(stateId),
        appTypeId > 0,
        StringUtils.isNotBlank(workCodeId),
        workCodeType
    );
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

    BigDecimal actualWorkTotalAmount();

    BigDecimal chargeableAmount();

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

  /**
   * This enums contains matrix to determine discount priority.
   *
   * @author alvin.llobrera@practiceinsight.io
   */
  public enum DiscountPriority {

    PRIORITY_31(31, true, true, true, true, "T"),
    PRIORITY_30(30, true, true, true, true, null),
    PRIORITY_29(29, true, true, true, false, "T"),
    PRIORITY_28(28, true, true, true, false, null),
    PRIORITY_27(27, true, true, false, true, "T"),
    PRIORITY_26(26, true, true, false, true, null),
    PRIORITY_25(25, true, true, false, false, "T"),
    PRIORITY_24(24, true, true, false, false, null),
    PRIORITY_23(23, true, false, true, true, "T"),
    PRIORITY_22(22, true, false, true, true, null),
    PRIORITY_21(21, true, false, true, false, "T"),
    PRIORITY_20(20, true, false, true, false, null),
    PRIORITY_19(19, true, false, false, true, "T"),
    PRIORITY_18(18, true, false, false, true, null),
    PRIORITY_17(17, true, false, false, false, "T"),
    PRIORITY_16(16, true, false, false, false, null),
    PRIORITY_15(15, false, true, true, true, "T"),
    PRIORITY_14(14, false, true, true, true, null),
    PRIORITY_13(13, false, true, true, false, "T"),
    PRIORITY_12(12, false, true, true, false, null),
    PRIORITY_11(11, false, true, false, true, "T"),
    PRIORITY_10(10, false, true, false, true, null),
    PRIORITY_9(9, false, true, false, false, "T"),
    PRIORITY_8(8, false, true, false, false, null),
    PRIORITY_7(7, false, false, true, true, "T"),
    PRIORITY_6(6, false, false, true, true, null),
    PRIORITY_5(5, false, false, true, false, "T"),
    PRIORITY_4(4, false, false, true, false, null),
    PRIORITY_3(3, false, false, false, true, "T"),
    PRIORITY_2(2, false, false, false, true, null),
    PRIORITY_1(1, false, false, false, false, "T"),
    PRIORITY_0(0, false, false, false, false, null);

    private int priorityNum;
    private boolean hasCaseTypeId;
    private boolean hasStateId;
    private boolean hasAppTypeId;
    private boolean hasWorkCodeId;
    private String workCodeType;

    @SuppressWarnings("all")
    DiscountPriority(int priorityNum,
                     boolean hasCaseTypeId,
                     boolean hasStateId,
                     boolean hasAppTypeId,
                     boolean hasWorkCodeId,
                     String workCodeType) {
      this.priorityNum = priorityNum;
      this.hasCaseTypeId = hasCaseTypeId;
      this.hasStateId = hasStateId;
      this.hasAppTypeId = hasAppTypeId;
      this.hasWorkCodeId = hasWorkCodeId;
      this.workCodeType = workCodeType;
    }

    public int getPriorityNum() {
      return priorityNum;
    }

    public boolean hasCaseTypeId() {
      return hasCaseTypeId;
    }

    public boolean hasStateId() {
      return hasStateId;
    }

    public boolean hasAppTypeId() {
      return hasAppTypeId;
    }

    public boolean hasWorkCodeId() {
      return hasWorkCodeId;
    }

    public String getWorkCodeType() {
      return workCodeType;
    }

    @SuppressWarnings({"ParameterNumber", "BooleanExpressionComplexity"})
    public static DiscountPriority findDiscountPriority(boolean hasCaseTypeId,
                                                        boolean hasStateId,
                                                        boolean hasAppTypeId,
                                                        boolean hasWorkCodeId,
                                                        String workCodeType) {
      return Arrays.stream(DiscountPriority.values())
          .filter(discountPriority ->
              discountPriority.hasCaseTypeId == hasCaseTypeId &&
                  discountPriority.hasStateId == hasStateId &&
                  discountPriority.hasAppTypeId == hasAppTypeId &&
                  discountPriority.hasWorkCodeId == hasWorkCodeId &&
                  Objects.equals(discountPriority.workCodeType, workCodeType)
          )
          .findFirst()
          .orElse(PRIORITY_0);
    }
  }
}

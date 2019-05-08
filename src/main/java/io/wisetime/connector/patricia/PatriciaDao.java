/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.patricia;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.inject.Inject;

import com.zaxxer.hikari.HikariDataSource;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.codejargon.fluentjdbc.api.FluentJdbc;
import org.codejargon.fluentjdbc.api.FluentJdbcBuilder;
import org.codejargon.fluentjdbc.api.mapper.Mappers;
import org.codejargon.fluentjdbc.api.query.Query;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import javax.annotation.Nullable;

import io.wisetime.generated.connect.UpsertTagRequest;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;

/**
 * Simple, unsophisticated access to the Patricia database.
 *
 * @author vadym
 */
public class PatriciaDao {

  private final Logger log = LoggerFactory.getLogger(PatriciaDao.class);
  private final FluentJdbc fluentJdbc;
  private final HikariDataSource hikariDataSource;

  @Inject
  PatriciaDao(HikariDataSource dataSource) {
    this.hikariDataSource = dataSource;
    fluentJdbc = new FluentJdbcBuilder().connectionProvider(dataSource).build();
  }

  void asTransaction(final Runnable runnable) {
    query().transaction().inNoResult(runnable);
  }

  boolean hasExpectedSchema() {
    log.info("Checking if Patricia DB has correct schema...");

    final Map<String, Set<String>> requiredTablesAndColumnsMap = Maps.newHashMap();
    requiredTablesAndColumnsMap.put(
        "vw_case_number",
        ImmutableSet.of("case_id", "case_number")
    );
    requiredTablesAndColumnsMap.put(
        "pat_case",
        ImmutableSet.of("case_id", "case_catch_word", "state_id", "application_type_id", "case_type_id")
    );
    requiredTablesAndColumnsMap.put(
        "person",
        ImmutableSet.of("login_id", "email", "hourly_rate")
    );
    requiredTablesAndColumnsMap.put(
        "casting",
        ImmutableSet.of("role_type_id", "actor_id", "case_id", "case_role_sequence")
    );
    requiredTablesAndColumnsMap.put(
        "pat_names",
        ImmutableSet.of("name_id", "currency_id")
    );
    requiredTablesAndColumnsMap.put(
        "pat_person_hourly_rate",
        ImmutableSet.of("pat_person_hourly_rate_id", "login_id", "work_code_id", "hourly_rate")
    );
    requiredTablesAndColumnsMap.put(
        "pat_work_code_discount_header",
        ImmutableSet.of("discount_id", "actor_id", "case_type_id", "state_id", "application_type_id", "work_code_type",
            "work_code_id", "discount_type"
        )
    );
    requiredTablesAndColumnsMap.put(
        "pat_work_code_discount_detail",
        ImmutableSet.of("discount_id", "amount", "price_change_formula")
    );
    requiredTablesAndColumnsMap.put(
        "budget_header",
        ImmutableSet.of("case_id", "budget_edit_date")
    );
    requiredTablesAndColumnsMap.put(
        "time_registration",
        ImmutableSet.of("work_code_id", "case_id", "registration_date_time", "login_id", "calendar_date", "worked_time",
            "debited_time", "time_transferred", "number_of_words", "worked_amount", "b_l_case_id", "time_comment_invoice",
            "time_comment", "time_reg_booked_date", "earliest_invoice_date"
        )
    );
    requiredTablesAndColumnsMap.put(
        "budget_line",
        ImmutableSet.of("b_l_seq_number", "work_code_id", "b_l_quantity", "b_l_org_quantity", "b_l_unit_price",
            "b_l_org_unit_price", "b_l_unit_price_no_discount", "deb_handlagg", "b_l_amount", "b_l_org_amount", "case_id",
            "show_time_comment", "registered_by", "earliest_inv_date", "b_l_comment", "recorded_date", "discount_prec",
            "discount_amount", "currency_id", "exchange_rate"
        )
    );

    final Map<String, List<String>> actualTablesAndColumnsMap = query().databaseInspection()
        .selectFromMetaData(meta -> meta.getColumns(null, null, null, null))
        .listResult(rs -> ImmutablePair.of(rs.getString("TABLE_NAME"), rs.getString("COLUMN_NAME")))
        .stream()
        .filter(pair -> requiredTablesAndColumnsMap.containsKey(pair.getKey().toLowerCase()))
        // transform to lower case to ensure we are comparing the same case
        .collect(groupingBy(pair -> pair.getKey().toLowerCase(), mapping(pair -> pair.getValue().toLowerCase(), toList())));

    return requiredTablesAndColumnsMap.entrySet().stream()
        .allMatch(entry -> actualTablesAndColumnsMap.containsKey(entry.getKey()) &&
            actualTablesAndColumnsMap.get(entry.getKey()).containsAll(entry.getValue())
        );
  }

  boolean canQueryDbDate() {
    try {
      getDbDate();
      return true;
    } catch (Exception ex) {
      return false;
    }
  }

  List<Case> findCasesOrderById(final long startIdExclusive, final int maxResults) {
    return query().select("SELECT TOP (?) vcn.case_id, vcn.case_number, pc.case_catch_word, " +
        " pc.case_type_id, pc.state_id, pc.application_type_id " +
        " FROM vw_case_number vcn JOIN pat_case pc ON vcn.case_id = pc.case_id " +
        " WHERE vcn.case_id > ? ORDER BY vcn.case_id ASC")
        .params(maxResults, startIdExclusive)
        .listResult(this::mapToCase);
  }

  Optional<String> findLoginIdByEmail(final String email) {
    return query().select("SELECT login_id FROM person WHERE LOWER(email) = ?")
        .params(email.toLowerCase())
        .firstResult(Mappers.singleString());
  }

  boolean loginIdExists(final String loginId) {
    return query().select("SELECT 1 FROM person WHERE LOWER(login_id) = ?") // Patricia login id is not case sensitive
        .params(loginId.toLowerCase())
        .firstResult(Mappers.singleInteger())
        .isPresent();
  }

  Optional<String> findCurrency(final long caseId, final int roleTypeId) {
    return query().select(
        "SELECT currency_id FROM pat_names WHERE name_id = " +
            "(SELECT DISTINCT actor_id FROM casting WHERE case_id = ? AND role_type_id = ? AND case_role_sequence = 1)")
        .params(caseId, roleTypeId)
        .firstResult(Mappers.singleString());
  }

  Optional<BigDecimal> findUserHourlyRate(final String workCodeId, final String loginId) {
    return query().select(
        " SELECT CASE " +
            "  WHEN EXISTS (" +
            "    SELECT wc.work_code_default_amount" +
            "    FROM work_code wc" +
            "      WHERE wc.work_code_id = :wc_id and wc.replace_amount = 1)" +
            "  THEN (" +
            "    SELECT wc.work_code_default_amount" +
            "    FROM work_code wc" +
            "      WHERE wc.work_code_id = :wc_id and wc.replace_amount = 1)" +
             "  WHEN EXISTS (" +
             "    SELECT pphr.hourly_rate" +
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
        .filter(Objects::nonNull)
        .firstResult(rs -> rs.getBigDecimal(1));
  }

  List<Discount> findDiscounts(final String workCodeId, final int roleTypeId, final long caseId) {
    return query().select(
        "SELECT "
            + "wcdh.discount_id, "
            + "wcdh.case_type_id, "
            + "wcdh.state_id, "
            + "wcdh.application_type_id, "
            + "wcdh.work_code_type, "
            + "wcdh.work_code_id, "
            + "wcdh.discount_type, "
            + "wcdd.amount, "
            + "wcdd.price_change_formula "
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

  Optional<Case> findCaseByCaseNumber(final String caseNumber) {
    return query().select("SELECT vcn.case_id, vcn.case_number, pc.case_catch_word, " +
        " pc.case_type_id, pc.state_id, pc.application_type_id " +
        " FROM vw_case_number vcn JOIN pat_case pc ON vcn.case_id = pc.case_id " +
        " WHERE vcn.case_number = ?")
        .params(caseNumber)
        .firstResult(this::mapToCase);
  }

  long updateBudgetHeader(final long caseId, final String recordalDate) {
    final boolean budgetHeaderExist =
        query().select("SELECT COUNT(*) FROM budget_header WHERE case_id = ?")
            .params(caseId)
            .singleResult(Mappers.singleLong()) > 0;

    if (budgetHeaderExist) {
      return query().update("UPDATE budget_header SET budget_edit_date = ? WHERE case_id = ?")
          .params(recordalDate, caseId)
          .run().affectedRows();
    } else {
      return query().update("INSERT INTO budget_header (case_id, budget_edit_date) VALUES (?, ?)")
          .params(caseId, recordalDate)
          .run().affectedRows();
    }
  }

  long addTimeRegistration(TimeRegistration timeRegistration) {
    return query().update(
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
            + "  B_L_SEQ_NUMBER,"
            + "  earliest_invoice_date "
            + ")"
            + "VALUES ("
            + "  :wc, :cid, :rd, :li, :cd, :wt, :dt, :tt, :nw, :wa, :bci, :tci, :tc, :blseq, :eid"
            + ")"
    )
        .namedParam("wc", timeRegistration.workCodeId())
        .namedParam("cid", timeRegistration.caseId())
        .namedParam("rd", timeRegistration.submissionDate())
        .namedParam("li", timeRegistration.userId())
        .namedParam("cd", timeRegistration.activityDate())
        .namedParam("wt", timeRegistration.actualHours())
        .namedParam("dt", timeRegistration.chargeableHours())
        .namedParam("tt", null)
        .namedParam("nw", null)
        .namedParam("wa", "0.00")
        .namedParam("bci", timeRegistration.caseId())
        .namedParam("tci", timeRegistration.comment())
        .namedParam("tc", timeRegistration.comment())
        .namedParam("blseq", timeRegistration.budgetLineSequenceNumber())
        .namedParam("eid", timeRegistration.submissionDate())
        .run().affectedRows();
  }

  long addBudgetLine(BudgetLine budgetLine) {
    return query().update(
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
            + "  exchange_rate, "
            + "  INDICATOR"
            + ") "
            + "VALUES ("
            + "  :bsn, :wc, :dt, :wt, :upd, :upd, :up, :li, :ttlblamt, :ttlbloamt, :cid, "
            + "  :stc, :li, :eid, :tci, :rd, :discperc, :discamt, :cur, :er, :indicator"
            + ")"
    )
        // make sure BigDecimal scales match the DB
        .namedParam("bsn", budgetLine.budgetLineSequenceNumber())
        .namedParam("wc", budgetLine.workCodeId())
        .namedParam("dt", budgetLine.chargeableWorkTotalHours().setScale(2, BigDecimal.ROUND_HALF_UP))
        .namedParam("wt", budgetLine.actualWorkTotalHours().setScale(2, BigDecimal.ROUND_HALF_UP))
        .namedParam("upd", budgetLine.effectiveHourlyRate().setScale(2, BigDecimal.ROUND_HALF_UP))
        .namedParam("up", budgetLine.hourlyRate().setScale(2, BigDecimal.ROUND_HALF_UP))
        .namedParam("li", budgetLine.userId())
        .namedParam("ttlblamt", budgetLine.chargeableAmount().setScale(2, BigDecimal.ROUND_HALF_UP))
        .namedParam("ttlbloamt", budgetLine.actualWorkTotalAmount().setScale(2, BigDecimal.ROUND_HALF_UP))
        .namedParam("cid", budgetLine.caseId())
        .namedParam("stc", 1)
        .namedParam("eid", budgetLine.submissionDate())
        .namedParam("tci", budgetLine.comment())
        .namedParam("rd", budgetLine.submissionDate())
        .namedParam("discperc", budgetLine.discountPercentage().setScale(6, BigDecimal.ROUND_HALF_UP))
        .namedParam("discamt", budgetLine.discountAmount().setScale(2, BigDecimal.ROUND_HALF_UP))
        .namedParam("cur", budgetLine.currency())
        .namedParam("er", 1)
        .namedParam("indicator", "TT")
        .run().affectedRows();
  }

  String getDbDate() {
    return query().select("SELECT getdate()").firstResult(Mappers.singleString()).get();
  }

  int findNextBudgetLineSeqNum(long caseId) {
    return query()
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
    final String priceChangeFormula = rset.getString(9);
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
        .priceChangeFormula(priceChangeFormula)
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

  private Query query() {
    return fluentJdbc.query();
  }

  void shutdown() {
    hikariDataSource.close();
  }

  /**
   * This maps some fields from pat_case and vw_case_number tables in Patricia DB.
   * It holds relevant data for a case, which are needed for creating tag and calculating discount priority.
   */
  @Value.Immutable
  public interface Case {

    long caseId();

    String caseNumber();

    @Nullable
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

    String priceChangeFormula();
  }

  @Value.Immutable
  public interface TimeRegistration {

    int budgetLineSequenceNumber();

    long caseId();

    String workCodeId();

    String userId();

    String submissionDate();

    String activityDate();

    BigDecimal actualHours();

    BigDecimal chargeableHours();

    String comment();
  }

  @Value.Immutable
  public interface BudgetLine {

    int budgetLineSequenceNumber();

    long caseId();

    String workCodeId();

    String userId();

    String submissionDate();

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

    BigDecimal actualHoursNoExpRating();

    BigDecimal actualHoursWithExpRating();

    BigDecimal chargeableHoursNoExpRating();

    BigDecimal chargeableHoursWithExpRating();

    LocalDateTime recordalDate();
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

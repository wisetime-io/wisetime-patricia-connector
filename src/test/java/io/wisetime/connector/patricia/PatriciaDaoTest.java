/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All rights reserved.
 */

package io.wisetime.connector.patricia;

import com.google.common.base.Preconditions;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Provider;

import com.github.javafaker.Faker;

import org.codejargon.fluentjdbc.api.FluentJdbc;
import org.codejargon.fluentjdbc.api.FluentJdbcBuilder;
import org.codejargon.fluentjdbc.api.mapper.Mappers;
import org.codejargon.fluentjdbc.api.query.Query;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.sql.DataSource;

import io.wisetime.connector.config.RuntimeConfig;

import static io.wisetime.connector.patricia.ConnectorLauncher.PatriciaConnectorConfigKey;
import static io.wisetime.connector.patricia.ConnectorLauncher.PatriciaDbModule;
import static io.wisetime.connector.patricia.PatriciaDao.BudgetLine;
import static io.wisetime.connector.patricia.PatriciaDao.Case;
import static io.wisetime.connector.patricia.PatriciaDao.Discount;
import static io.wisetime.connector.patricia.PatriciaDao.DiscountPriority;
import static io.wisetime.connector.patricia.PatriciaDao.TimeRegistration;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author alvin.llobrera@practiceinsight.io
 */
class PatriciaDaoTest {

  private static final String TEST_JDBC_URL = "jdbc:h2:mem:test_patricia_db;DB_CLOSE_DELAY=-1";
  private static final RandomDataGenerator RANDOM_DATA_GENERATOR = new RandomDataGenerator();
  private static final Faker FAKER = new Faker();
  private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  private static PatriciaDao patriciaDao;
  private static FluentJdbc fluentJdbc;

  @BeforeAll
  static void setup() {
    RuntimeConfig.setProperty(PatriciaConnectorConfigKey.PATRICIA_JDBC_URL, TEST_JDBC_URL);
    RuntimeConfig.setProperty(PatriciaConnectorConfigKey.PATRICIA_JDBC_USERNAME, "test");
    RuntimeConfig.setProperty(PatriciaConnectorConfigKey.PATRICIA_JDBC_PASSWORD, "test");

    final Injector injector = Guice.createInjector(
        new PatriciaDbModule(), new FlywayPatriciaTestDbModule()
    );

    patriciaDao = injector.getInstance(PatriciaDao.class);
    fluentJdbc = new FluentJdbcBuilder().connectionProvider(injector.getInstance(DataSource.class)).build();

    // Apply Patricia DB schema to test db
    injector.getInstance(Flyway.class).migrate();
  }

  @BeforeAll
  static void tearDown() {
    RuntimeConfig.clearProperty(PatriciaConnectorConfigKey.PATRICIA_JDBC_URL);
    RuntimeConfig.clearProperty(PatriciaConnectorConfigKey.PATRICIA_JDBC_USERNAME);
    RuntimeConfig.clearProperty(PatriciaConnectorConfigKey.PATRICIA_JDBC_PASSWORD);
  }

  @BeforeEach
  void setupTests() {
    Preconditions.checkState(
        // We don't want to accidentally truncate production tables
        RuntimeConfig.getString(PatriciaConnectorConfigKey.PATRICIA_JDBC_URL).orElse("").equals(TEST_JDBC_URL)
    );
    Query query = fluentJdbc.query();
    query.update("DELETE FROM vw_case_number").run();
    query.update("DELETE FROM pat_case").run();
    query.update("DELETE FROM person").run();
    query.update("DELETE FROM casting").run();
    query.update("DELETE FROM pat_names").run();
    query.update("DELETE FROM pat_person_hourly_rate").run();
    query.update("DELETE FROM budget_header").run();
    query.update("DELETE FROM time_registration").run();
    query.update("DELETE FROM budget_line").run();
    removeAllDiscounts();
  }

  @Test
  void findIssuesOrderedById() {
    final List<Case> cases = RANDOM_DATA_GENERATOR.randomCase(100);

    // ensure cased id are sequential
    List<Case> savedCases = IntStream.range(0, cases.size())
        .mapToObj(idx -> ImmutableCase.builder()
            .from(cases.get(idx))
            .caseId(idx + 1)  // case id start at 1
            .build())
        .peek(this::saveCase)
        .collect(Collectors.toList());

    assertThat(patriciaDao.findCasesOrderById(0, 100))
        .as("Should be able retrieve matching issue")
        .containsExactlyElementsOf(savedCases);
    assertThat(patriciaDao.findCasesOrderById(25, 5))
        .as("Should be able retrieve matching issue")
        .containsExactlyElementsOf(savedCases.subList(25, 30));
    assertThat(patriciaDao.findCasesOrderById(101, 5))
        .as("No Jira issue should be returned when no issue matches the start ID")
        .isEmpty();
  }

  @Test
  void findLoginByEmail() {
    savePerson("foobar", "foobar@baz.com", FAKER.number().randomNumber());

    assertThat(patriciaDao.findLoginByEmail("foobar@baz.com"))
        .as("Username should be returned if it exists in DB.")
        .contains("foobar");
    assertThat(patriciaDao.findLoginByEmail("Foobar@baz.com"))
        .as("Email should not be case sensitive")
        .contains("foobar");
    assertThat(patriciaDao.findLoginByEmail("foo.bar@baz.com"))
        .as("Should return empty if email is not found in DB")
        .isEmpty();
  }

  @Test
  void findCurrency() {
    int caseId = FAKER.number().randomDigitNotZero();
    int roleTypeId = FAKER.number().randomDigitNotZero();
    int actorId = FAKER.number().randomDigitNotZero();
    String currency = FAKER.currency().code();

    fluentJdbc.query().update("INSERT INTO pat_names (name_id, currency_id) VALUES (?, ?)")
        .params(actorId, currency)
        .run();
    saveCasting(actorId, caseId, roleTypeId);

    assertThat(patriciaDao.findCurrency(caseId, roleTypeId))
        .as("should be able to retrieve currency defined for a case")
        .contains(currency);
    assertThat(patriciaDao.findCurrency(FAKER.number().randomDigit(), roleTypeId))
        .as("no defined currency for that case id")
        .isEmpty();
  }

  @Test
  void findUserHourlyRate() {
    double personGeneralHourlyRate = FAKER.number().randomDigitNotZero();
    double personHourlyRateForWorkCode = personGeneralHourlyRate + 10;

    savePerson("username1", "username1@email.com", personGeneralHourlyRate);
    savePerson("username2", "username2@email.com", personGeneralHourlyRate);
    savePersonWorkCodeRate("username1", "workCode", personHourlyRateForWorkCode);

    assertThat(patriciaDao.findUserHourlyRate("workCode", "username1").get())
        .as("should get the user's hourly rate for workcode if set")
        .isEqualByComparingTo(BigDecimal.valueOf(personHourlyRateForWorkCode));

    assertThat(patriciaDao.findUserHourlyRate("workCode", "username2").get())
        .as("should get the user's general hourly rate if no rate set fpr work code")
        .isEqualByComparingTo(BigDecimal.valueOf(personGeneralHourlyRate));

    assertThat(patriciaDao.findUserHourlyRate("workCode", "username3"))
        .as("should not retrieve any hourly rate if none is set")
        .isEmpty();
  }

  @Test
  void findDiscounts() {
    int caseId = FAKER.number().randomDigitNotZero();
    int roleTypeId = FAKER.number().randomDigitNotZero();
    int actorId = FAKER.number().randomDigitNotZero();
    int discountId = FAKER.number().randomDigitNotZero();
    String workCode = FAKER.lorem().word();

    saveCasting(actorId, caseId, roleTypeId);

    Arrays.stream(DiscountPriority.values())
        .forEach(discountPriority -> {

          removeAllDiscounts();
          saveRandomDiscount(discountPriority, discountId, actorId, workCode);

          List<Discount> discounts = patriciaDao.findDiscounts(
              discountPriority.hasWorkCodeId() ? workCode : null,
              roleTypeId,
              caseId
          );

          assertThat(discounts).hasSize(1);
          assertThat(discounts.get(0).priority())
              .as("should be retrieved with correct discount priority")
              .isEqualTo(discountPriority.getPriorityNum());
        });
  }

  @Test
  void findCaseByTagName() {
    Case patriciaCase = RANDOM_DATA_GENERATOR.randomCase();
    saveCase(patriciaCase);

    assertThat(patriciaDao.findCaseByTagName(patriciaCase.caseNumber()))
        .as("Should return Patricia case if it exists in DB")
        .contains(patriciaCase);
    assertThat(patriciaDao.findCaseByTagName(patriciaCase.caseNumber() + "123"))
        .as("Should return empty if case is not in DB")
        .isEmpty();
  }

  @Test
  void updateBudgetHeader() {
    long caseId = FAKER.number().numberBetween(1, 100);
    String recordalDate = LocalDateTime.now().format(DATE_TIME_FORMATTER);

    patriciaDao.updateBudgetHeader(caseId, recordalDate);
    assertThat(getBudgetHeaderEditDate(caseId))
        .as("should be able to save the budget header record")
        .isEqualTo(recordalDate);

    String newRecordalDate = LocalDateTime.now().minusDays(1).format(DATE_TIME_FORMATTER);
    patriciaDao.updateBudgetHeader(caseId, newRecordalDate);
    assertThat(getBudgetHeaderEditDate(caseId))
        .as("should be able to update the budget header's record date")
        .isEqualTo(newRecordalDate);
  }

  @Test
  void addTimeRegistration() {
    final long caseId = FAKER.number().randomNumber();
    final TimeRegistration timeRegistration = ImmutableTimeRegistration.builder()
        .caseId(caseId)
        .workCodeId(FAKER.lorem().word())
        .userId(FAKER.name().firstName())
        .recordalDate(LocalDateTime.now().format(DATE_TIME_FORMATTER))
        .actualHours(BigDecimal.valueOf(FAKER.number().randomDigitNotZero()))
        .chargeableHours(BigDecimal.valueOf(FAKER.number().randomDigitNotZero()))
        .comment(FAKER.lorem().sentence())
        .build();

    patriciaDao.addTimeRegistration(timeRegistration);

    fluentJdbc.query().select(
        "SELECT work_code_id, case_id, registration_date_time, login_id, calendar_date, worked_time, debited_time, " +
            "   time_transferred, number_of_words, worked_amount, b_l_case_id, time_comment_invoice, time_comment, " +
            "   time_reg_booked_date, earliest_invoice_date " +
            " FROM time_registration WHERE case_id = ?")
        .params(caseId)
        .singleResult(rs -> {
          assertThat(rs.getString(1))
              .as("should have save correct work code id")
              .isEqualTo(timeRegistration.workCodeId());
          assertThat(rs.getLong(2))
              .as("should have save correct case id")
              .isEqualTo(timeRegistration.caseId());
          assertThat(rs.getString(3))
              .as("should have save correct recordal date")
              .isEqualTo(timeRegistration.recordalDate());
          assertThat(rs.getString(4))
              .as("should have save correct user id date")
              .isEqualTo(timeRegistration.userId());
          assertThat(rs.getString(5))
              .isEqualTo(timeRegistration.recordalDate());
          assertThat(rs.getBigDecimal(6))
              .as("should have set correct actual hours")
              .isEqualByComparingTo(timeRegistration.actualHours());
          assertThat(rs.getBigDecimal(7))
              .as("should have set corrrect chargeable hours")
              .isEqualByComparingTo(timeRegistration.chargeableHours());
          assertThat(rs.getString(8)).isEqualTo("!");
          assertThat(rs.getInt(9)).isEqualTo(0);
          assertThat(rs.getDouble(10)).isEqualTo(0.00);
          assertThat(rs.getLong(11)).isEqualTo(timeRegistration.caseId());
          assertThat(rs.getString(12)).isEqualTo(timeRegistration.comment());
          assertThat(rs.getString(13)).isEqualTo(timeRegistration.comment());
          assertThat(rs.getString(14)).isEqualTo(timeRegistration.recordalDate());
          assertThat(rs.getString(15)).isEqualTo(timeRegistration.recordalDate());

          return Void.TYPE;
        });
  }

  @Test
  void addBudgetLine() {
    final long caseId = FAKER.number().randomDigitNotZero();
    final BudgetLine budgetLine = ImmutableBudgetLine.builder()
        .caseId(caseId)
        .workCodeId(FAKER.lorem().word())
        .userId(FAKER.name().name())
        .recordalDate(LocalDateTime.now().format(DATE_TIME_FORMATTER))
        .currency(FAKER.currency().code())
        .hourlyRate(BigDecimal.valueOf(FAKER.number().randomDigitNotZero()))
        .effectiveHourlyRate(BigDecimal.valueOf(FAKER.number().randomDigitNotZero()))
        .actualWorkTotalHours(BigDecimal.valueOf(FAKER.number().randomDigitNotZero()))
        .chargeableWorkTotalHours(BigDecimal.valueOf(FAKER.number().randomDigitNotZero()))
        .actualWorkTotalAmount(BigDecimal.valueOf(FAKER.number().randomDigitNotZero()))
        .chargeableAmount(BigDecimal.valueOf(FAKER.number().randomDigitNotZero()))
        .discountPercentage(BigDecimal.valueOf(FAKER.number().numberBetween(10, 100)))
        .discountAmount(BigDecimal.valueOf(FAKER.number().randomDigitNotZero()))
        .comment(FAKER.lorem().sentence())
        .build();

    patriciaDao.addBudgetLine(budgetLine);

    fluentJdbc.query().select(
        "SELECT b_l_seq_number, work_code_id, b_l_quantity, b_l_org_quantity, b_l_unit_price, " +
            "   b_l_org_unit_price, b_l_unit_price_no_discount, deb_handlagg, b_l_amount, b_l_org_amount, case_id," +
            "   show_time_comment, registered_by, earliest_inv_date, b_l_comment, recorded_date, discount_prec, " +
            "   discount_amount, currency_id, exchange_rate" +
            " FROM budget_line WHERE case_id = ?")
        .params(caseId)
        .singleResult(rs -> {
          // assert if correct values are set
          assertThat(rs.getInt(1))
              .as("Initial sequence should be 1")
              .isEqualTo(1);
          assertThat(rs.getString(2)).isEqualTo(budgetLine.workCodeId());
          assertThat(rs.getBigDecimal(3)).isEqualByComparingTo(budgetLine.chargeableWorkTotalHours());
          assertThat(rs.getBigDecimal(4)).isEqualByComparingTo(budgetLine.actualWorkTotalHours());
          assertThat(rs.getBigDecimal(5)).isEqualByComparingTo(budgetLine.effectiveHourlyRate());
          assertThat(rs.getBigDecimal(6)).isEqualByComparingTo(budgetLine.effectiveHourlyRate());
          assertThat(rs.getBigDecimal(7)).isEqualByComparingTo(budgetLine.hourlyRate());
          assertThat(rs.getString(8)).isEqualTo(budgetLine.userId());
          assertThat(rs.getBigDecimal(9)).isEqualByComparingTo(budgetLine.chargeableAmount());
          assertThat(rs.getBigDecimal(10)).isEqualByComparingTo(budgetLine.actualWorkTotalAmount());
          assertThat(rs.getLong(11)).isEqualTo(budgetLine.caseId());
          assertThat(rs.getInt(12)).isEqualTo(1);
          assertThat(rs.getString(13)).isEqualTo(budgetLine.userId());
          assertThat(rs.getString(14)).isEqualTo(budgetLine.recordalDate());
          assertThat(rs.getString(15)).isEqualTo(budgetLine.comment());
          assertThat(rs.getString(16)).isEqualTo(budgetLine.recordalDate());
          assertThat(rs.getBigDecimal(17)).isEqualByComparingTo(budgetLine.discountPercentage());
          assertThat(rs.getBigDecimal(18)).isEqualByComparingTo(budgetLine.discountAmount());
          assertThat(rs.getString(19)).isEqualTo(budgetLine.currency());
          assertThat(rs.getInt(20)).isEqualTo(1);

          return Void.TYPE;
        });

    assertThat(patriciaDao.findNextBudgetLineSeqNum(caseId))
        .as("next sequence number for budget line should be 2")
        .isEqualTo(2);
  }

  private void saveCase(Case patCase) {
    fluentJdbc.query()
        .update("INSERT INTO vw_case_number (case_id, case_number) VALUES (?, ?)")
        .params(patCase.caseId(), patCase.caseNumber())
        .run();

    fluentJdbc.query()
        .update("INSERT INTO pat_case (case_id, case_catch_word, state_id, application_type_id, case_type_id) " +
            "VALUES (?, ?, ?, ?, ?)")
        .params(patCase.caseId(), patCase.caseCatchWord(), patCase.stateId(), patCase.appId(), patCase.caseTypeId())
        .run();
  }

  private void savePerson(String loginId, String email, double hourlyRate) {
    fluentJdbc.query().update("INSERT INTO person (login_id, email, hourly_rate) VALUES (?, ?, ?)")
        .params(loginId, email, hourlyRate)
        .run();
  }

  private void savePersonWorkCodeRate(String loginId, String workCode, double hourlyRate) {
    fluentJdbc.query().update(
        "INSERT INTO pat_person_hourly_rate (pat_person_hourly_rate_id, login_id, work_code_id, hourly_rate) " +
        " VALUES (?, ?, ?, ?)")
        .params(FAKER.number().randomDigit(), loginId, workCode, hourlyRate)
        .run();
  }

  private void saveRandomDiscount(DiscountPriority discountPriority,
                                  int discountId,
                                  int userId,
                                  String workCode) {
    fluentJdbc.query().update("INSERT INTO pat_work_code_discount_header (discount_id, actor_id, case_type_id, " +
        " state_id, application_type_id, work_code_type, work_code_id, discount_type) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
        .params(
            discountId,
            userId,
            discountPriority.hasCaseTypeId() ? FAKER.number().numberBetween(1, 100) : null,
            discountPriority.hasStateId() ? FAKER.bothify("?#") : null,
            discountPriority.hasAppTypeId() ? FAKER.number().numberBetween(1, 100) : null,
            discountPriority.getWorkCodeType(),
            discountPriority.hasWorkCodeId() ? workCode : null,
            FAKER.number().numberBetween(1, 2)
        )
        .run();

    fluentJdbc.query().update("INSERT INTO pat_work_code_discount_detail (discount_id, amount) " +
        " VALUES (?, ?)")
        .params(discountId, FAKER.number().randomDigit())
        .run();

  }

  private void saveCasting(int actorId, int caseId, int roleTypeId) {
    fluentJdbc.query().update(
        "INSERT INTO casting (actor_id, case_id, role_type_id, case_role_sequence) VALUES (?, ?, ?, ?)")
        .params(actorId, caseId, roleTypeId, 1)
        .run();
  }

  private String getBudgetHeaderEditDate(long caseId) {
    return fluentJdbc.query().select("SELECT budget_edit_date FROM budget_header WHERE case_id = ?")
        .params(caseId)
        .singleResult(Mappers.singleString());
  }

  private void removeAllDiscounts() {
    fluentJdbc.query().update("DELETE FROM pat_work_code_discount_header").run();
    fluentJdbc.query().update("DELETE FROM pat_work_code_discount_detail").run();
  }

  public static class FlywayPatriciaTestDbModule extends AbstractModule {

    @Override
    protected void configure() {
      bind(Flyway.class).toProvider(FlywayPatriciaProvider.class);
    }

    private static class FlywayPatriciaProvider implements Provider<Flyway> {

      @Inject
      private Provider<DataSource> dataSourceProvider;

      @Override
      public Flyway get() {
        Flyway flyway = new Flyway();
        flyway.setDataSource(dataSourceProvider.get());
        flyway.setBaselineVersion(MigrationVersion.fromVersion("0"));
        flyway.setBaselineOnMigrate(true);
        flyway.setLocations("patricia_db_schema/");
        return flyway;
      }
    }
  }
}

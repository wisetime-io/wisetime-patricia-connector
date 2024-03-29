/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All rights reserved.
 */

package io.wisetime.connector.patricia;

import static io.wisetime.connector.patricia.ConnectorLauncher.PatriciaConnectorConfigKey;
import static io.wisetime.connector.patricia.ConnectorLauncher.PatriciaDbModule;
import static io.wisetime.connector.patricia.PatriciaDao.BudgetLine;
import static io.wisetime.connector.patricia.PatriciaDao.Case;
import static io.wisetime.connector.patricia.PatriciaDao.Discount;
import static io.wisetime.connector.patricia.PatriciaDao.DiscountPriority;
import static io.wisetime.connector.patricia.PatriciaDao.TimeRegistration;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.javafaker.Faker;
import com.google.common.base.Preconditions;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Provider;
import com.zaxxer.hikari.HikariDataSource;
import io.wisetime.connector.config.RuntimeConfig;
import io.wisetime.connector.patricia.PatriciaDao.RateCurrency;
import io.wisetime.connector.patricia.PatriciaDao.WorkCode;
import io.wisetime.test_docker.ContainerRuntimeSpec;
import io.wisetime.test_docker.DockerLauncher;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.codejargon.fluentjdbc.api.FluentJdbc;
import org.codejargon.fluentjdbc.api.FluentJdbcBuilder;
import org.codejargon.fluentjdbc.api.mapper.Mappers;
import org.codejargon.fluentjdbc.api.query.Query;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * @author alvin.llobrera@practiceinsight.io
 */
class PatriciaDaoTest {

  private static final RandomDataGenerator RANDOM_DATA_GENERATOR = new RandomDataGenerator();
  private static final Faker FAKER = new Faker();
  private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  private static PatriciaDao patriciaDao;
  private static FluentJdbc fluentJdbc;

  private static String jdbcUrl;

  @BeforeAll
  static void setup() throws InterruptedException {
    DockerLauncher staticLauncher = DockerLauncher.instance();
    PlainSqlServer sqlServer = new PlainSqlServer();
    ContainerRuntimeSpec container = staticLauncher.createContainer(sqlServer);
    jdbcUrl = sqlServer.getJdbcUrl(container);
    RuntimeConfig.setProperty(PatriciaConnectorConfigKey.PATRICIA_JDBC_URL, jdbcUrl);
    RuntimeConfig.setProperty(PatriciaConnectorConfigKey.PATRICIA_JDBC_USERNAME, sqlServer.getUsername());
    RuntimeConfig.setProperty(PatriciaConnectorConfigKey.PATRICIA_JDBC_PASSWORD, sqlServer.getPassword());

    // If we don't wait the authentication to sql server seems to fail more often than not...
    Thread.sleep(10000);
    final Injector injector = Guice.createInjector(
        new PatriciaDbModule(), new FlywayPatriciaTestDbModule()
    );

    patriciaDao = injector.getInstance(PatriciaDao.class);
    fluentJdbc = new FluentJdbcBuilder().connectionProvider(injector.getInstance(HikariDataSource.class)).build();

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
        RuntimeConfig.getString(PatriciaConnectorConfigKey.PATRICIA_JDBC_URL).orElse("").equals(jdbcUrl)
    );
    Query query = fluentJdbc.query();
    query.update("DELETE FROM vw_case_number").run();
    query.update("DELETE FROM pat_case").run();
    query.update("DELETE FROM person").run();
    query.update("DELETE FROM casting").run();
    query.update("DELETE FROM PAT_NAMES_ENTITY").run();
    query.update("DELETE FROM pat_names").run();
    query.update("DELETE FROM pat_person_hourly_rate").run();
    query.update("DELETE FROM budget_header").run();
    query.update("DELETE FROM time_registration").run();
    query.update("DELETE FROM budget_line").run();
    query.update("DELETE FROM work_code").run();
    query.update("DELETE FROM chargeing_price_list").run();
    query.update("DELETE FROM case_category").run();
    query.update("DELETE FROM case_type_definition").run();
    query.update("DELETE FROM CASE_TYPE_DEFAULT_STATE").run();
    query.update("DELETE FROM RENEWAL_PRICE_LIST").run();
    query.update("DELETE FROM WORK_CODE_TEXT").run();
    query.update("DELETE FROM LANGUAGE_CODE").run();
    removeAllDiscounts();
  }

  @Test
  void hasExpectedSchema() {
    assertThat(patriciaDao.hasExpectedSchema())
        .as("Flyway should freshly applied the expected Patricia DB schema")
        .isTrue();

    Query query = fluentJdbc.query();
    query.update("ALTER TABLE vw_case_number DROP column case_number").run();
    assertThat(patriciaDao.hasExpectedSchema())
        .as("A missing column should be detected")
        .isFalse();

    query.update("ALTER TABLE vw_case_number ADD case_number varchar(40) NOT NULL").run();
    assertThat(patriciaDao.hasExpectedSchema())
        .as("The missing column has been added")
        .isTrue();
  }

  @Test
  void canQueryDbDate() {
    assertThat(patriciaDao.canQueryDbDate())
        .as("should return true if connected to a database")
        .isTrue();
  }

  @Test
  void casesCount_none_found() {
    assertThat(patriciaDao.casesCount())
        .as("There are no cases in the database")
        .isEqualTo(0);
  }

  @Test
  void casesCount_all() {
    final int casesNumber = 10;
    RANDOM_DATA_GENERATOR.randomCase(casesNumber)
        .forEach(this::saveCase);

    assertThat(patriciaDao.casesCount())
        .as("All cases should be counted")
        .isEqualTo(casesNumber);
  }

  @Test
  void findIssuesOrderedById() {
    final List<Case> cases = RANDOM_DATA_GENERATOR.randomCase(100);

    // ensure cased id are sequential
    List<Case> savedCases = IntStream.range(0, cases.size())
        .mapToObj(idx -> cases.get(idx).caseId(idx + 1)) // case id start at 1
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
  void findUserByEmail() {
    savePerson("foobar", "foobar@baz.com", FAKER.number().randomNumber());

    assertThat(patriciaDao.findLoginIdByEmail("foobar@baz.com"))
        .as("Username should be returned if it exists in DB.")
        .contains("foobar");
    assertThat(patriciaDao.findLoginIdByEmail("Foobar@baz.com"))
        .as("Email should not be case sensitive")
        .contains("foobar");
    assertThat(patriciaDao.findLoginIdByEmail("foo.bar@baz.com"))
        .as("Should return empty if email is not found in DB")
        .isEmpty();
  }

  @Test
  void findUserByLoginId() {
    savePerson("foobar", "foobar@baz.com", FAKER.number().randomNumber());

    assertThat(patriciaDao.loginIdExists("foobar"))
        .as("Login ID exists in DB.")
        .isTrue();
    assertThat(patriciaDao.loginIdExists("FOOBAR"))
        .as("Login ID should not be case sensitive")
        .isTrue();
    assertThat(patriciaDao.loginIdExists("foo.bar"))
        .as("Login ID not in DB")
        .isFalse();
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
    assertThat(patriciaDao.findCurrency(caseId + 1, roleTypeId))
        .as("no defined currency for that case id")
        .isEmpty();
  }

  @Test
  void getSystemDefaultCurrency() {
    String currency = FAKER.currency().code();

    fluentJdbc.query().update("INSERT INTO CURRENCY (CURRENCY_ID, default_currency) VALUES (?, ?)")
        .params(currency, 1)
        .run();
    fluentJdbc.query().update("INSERT INTO CURRENCY (CURRENCY_ID, default_currency) VALUES (?, ?)")
        .params(FAKER.currency().code(), 0)
        .run();

    assertThat(patriciaDao.getSystemDefaultCurrency())
        .as("should be able to retrieve currency defined for a case")
        .contains(currency);
  }

  @Test
  void findHourlyRate_workCode_caseId() {
    double personGeneralHourlyRate = FAKER.number().randomDigitNotZero();
    double personHourlyRateForWorkCode = personGeneralHourlyRate + 10;
    double defaultRateForWorkCode = personHourlyRateForWorkCode + 10;
    long caseId = FAKER.number().numberBetween(1, 1000000);
    String currency = FAKER.currency().code();

    savePerson("username1", "username1@email.com", personGeneralHourlyRate);
    savePerson("username2", "username2@email.com", personGeneralHourlyRate);
    // should find entry with work code and case set
    savePersonWorkCodeRate("username1", null, currency, personHourlyRateForWorkCode * 2, null);
    savePersonWorkCodeRate("username1", "workCode", currency, personHourlyRateForWorkCode * 2, null);
    savePersonWorkCodeRate("username1", null, currency, personHourlyRateForWorkCode * 2, caseId);
    savePersonWorkCodeRate("username1", "workCode", currency, personHourlyRateForWorkCode, caseId);
    saveDefaultWorkCodeRate("workCode", defaultRateForWorkCode, 0);
    saveDefaultWorkCodeRate("workCode2", defaultRateForWorkCode, 1);

    assertThat(patriciaDao.findWorkCodeDefaultHourlyRate("workCode2").get())
        .as("should get the default work code rate when replace_amount is 1")
        .isEqualByComparingTo(BigDecimal.valueOf(defaultRateForWorkCode));

    assertThat(patriciaDao.findPatPersonHourlyRate(0L, "workCode", "username1").get())
        .as("should get the user's hourly rate for work code if set "
            + "and skip default work code rate when replace_amount is not 1")
        .isEqualTo(RateCurrency.builder()
            .hourlyRate(BigDecimal.valueOf(personHourlyRateForWorkCode).setScale(2))
            .currencyId(currency)
            .build());

    assertThat(patriciaDao.findPersonDefaultHourlyRate("username2").get())
        .as("should get the user's general hourly rate if no rate set for work code")
        .isEqualByComparingTo(BigDecimal.valueOf(personGeneralHourlyRate));

    assertThat(patriciaDao.findPersonDefaultHourlyRate("username3"))
        .as("should not retrieve any hourly rate if none is set")
        .isEmpty();
  }

  @Test
  void findHourlyRate_workCode() {
    double personGeneralHourlyRate = FAKER.number().randomDigitNotZero();
    double personHourlyRateForWorkCode = personGeneralHourlyRate + 10;
    double defaultRateForWorkCode = personHourlyRateForWorkCode + 10;
    long caseId = FAKER.number().numberBetween(1, 1000000);
    String currency = FAKER.currency().code();

    savePerson("username1", "username1@email.com", personGeneralHourlyRate);
    savePerson("username2", "username2@email.com", personGeneralHourlyRate);
    // should find entry with work code set
    savePersonWorkCodeRate("username1", null, currency, personHourlyRateForWorkCode * 2, null);
    savePersonWorkCodeRate("username1", "workCode", currency, personHourlyRateForWorkCode, null);
    saveDefaultWorkCodeRate("workCode", defaultRateForWorkCode, 0);
    saveDefaultWorkCodeRate("workCode2", defaultRateForWorkCode, 1);

    assertThat(patriciaDao.findWorkCodeDefaultHourlyRate("workCode2").get())
        .as("should get the default work code rate when replace_amount is 1")
        .isEqualByComparingTo(BigDecimal.valueOf(defaultRateForWorkCode));

    assertThat(patriciaDao.findPatPersonHourlyRate(0L, "workCode", "username1").get())
        .as("should get the user's hourly rate for work code if set "
            + "and skip default work code rate when replace_amount is not 1")
        .isEqualTo(RateCurrency.builder()
            .hourlyRate(BigDecimal.valueOf(personHourlyRateForWorkCode).setScale(2))
            .currencyId(currency)
            .build());

    assertThat(patriciaDao.findPersonDefaultHourlyRate("username2").get())
        .as("should get the user's general hourly rate if no rate set for work code")
        .isEqualByComparingTo(BigDecimal.valueOf(personGeneralHourlyRate));

    assertThat(patriciaDao.findPersonDefaultHourlyRate("username3"))
        .as("should not retrieve any hourly rate if none is set")
        .isEmpty();
  }

  @Test
  void findHourlyRate_caseId() {
    double personGeneralHourlyRate = FAKER.number().randomDigitNotZero();
    double personHourlyRateForWorkCode = personGeneralHourlyRate + 10;
    double defaultRateForWorkCode = personHourlyRateForWorkCode + 10;
    long caseId = FAKER.number().numberBetween(1, 1000000);
    String currency = FAKER.currency().code();

    savePerson("username1", "username1@email.com", personGeneralHourlyRate);
    savePerson("username2", "username2@email.com", personGeneralHourlyRate);
    // should find entry with case set
    savePersonWorkCodeRate("username1", null, currency, personHourlyRateForWorkCode * 2, null);
    savePersonWorkCodeRate("username1", "workCode", currency, personHourlyRateForWorkCode, null);
    savePersonWorkCodeRate("username1", null, currency, personHourlyRateForWorkCode, caseId);
    saveDefaultWorkCodeRate("workCode", defaultRateForWorkCode, 0);
    saveDefaultWorkCodeRate("workCode2", defaultRateForWorkCode, 1);

    assertThat(patriciaDao.findWorkCodeDefaultHourlyRate("workCode2").get())
        .as("should get the default work code rate when replace_amount is 1")
        .isEqualByComparingTo(BigDecimal.valueOf(defaultRateForWorkCode));

    assertThat(patriciaDao.findPatPersonHourlyRate(0L, "workCode", "username1").get())
        .as("should get the user's hourly rate for work code if set "
            + "and skip default work code rate when replace_amount is not 1")
        .isEqualTo(RateCurrency.builder()
            .hourlyRate(BigDecimal.valueOf(personHourlyRateForWorkCode).setScale(2))
            .currencyId(currency)
            .build());

    assertThat(patriciaDao.findPersonDefaultHourlyRate("username2").get())
        .as("should get the user's general hourly rate if no rate set for work code")
        .isEqualByComparingTo(BigDecimal.valueOf(personGeneralHourlyRate));

    assertThat(patriciaDao.findPersonDefaultHourlyRate("username3"))
        .as("should not retrieve any hourly rate if none is set")
        .isEmpty();
  }

  @Test
  void findHourlyRate() {
    double personGeneralHourlyRate = FAKER.number().randomDigitNotZero();
    double personHourlyRateForWorkCode = personGeneralHourlyRate + 10;
    double defaultRateForWorkCode = personHourlyRateForWorkCode + 10;
    long caseId = FAKER.number().numberBetween(1, 1000000);
    String currency = FAKER.currency().code();

    savePerson("username1", "username1@email.com", personGeneralHourlyRate);
    savePerson("username2", "username2@email.com", personGeneralHourlyRate);
    // should find entry without work code and case set
    savePersonWorkCodeRate("username1", null, currency, personHourlyRateForWorkCode, null);
    saveDefaultWorkCodeRate("workCode", defaultRateForWorkCode, 0);
    saveDefaultWorkCodeRate("workCode2", defaultRateForWorkCode, 1);

    assertThat(patriciaDao.findWorkCodeDefaultHourlyRate("workCode2").get())
        .as("should get the default work code rate when replace_amount is 1")
        .isEqualByComparingTo(BigDecimal.valueOf(defaultRateForWorkCode));

    assertThat(patriciaDao.findPatPersonHourlyRate(0L, "workCode", "username1").get())
        .as("should get the user's hourly rate for work code if set "
            + "and skip default work code rate when replace_amount is not 1")
        .isEqualTo(RateCurrency.builder()
            .hourlyRate(BigDecimal.valueOf(personHourlyRateForWorkCode).setScale(2))
            .currencyId(currency)
            .build());

    assertThat(patriciaDao.findPersonDefaultHourlyRate("username2").get())
        .as("should get the user's general hourly rate if no rate set for work code")
        .isEqualByComparingTo(BigDecimal.valueOf(personGeneralHourlyRate));

    assertThat(patriciaDao.findPersonDefaultHourlyRate("username3"))
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
              discountPriority.isHasWorkCodeId() ? workCode : null,
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
  void findCaseByCaseNumber() {
    Case patriciaCase = RANDOM_DATA_GENERATOR.randomCase();
    saveCase(patriciaCase);

    assertThat(patriciaDao.findCaseByCaseNumber(patriciaCase.caseNumber()))
        .as("Should return Patricia case if it exists in DB")
        .contains(patriciaCase);
    assertThat(patriciaDao.findCaseByCaseNumber(patriciaCase.caseNumber() + "123"))
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
        .startsWith(recordalDate);

    String newRecordalDate = LocalDateTime.now().minusDays(1).format(DATE_TIME_FORMATTER);
    patriciaDao.updateBudgetHeader(caseId, newRecordalDate);
    assertThat(getBudgetHeaderEditDate(caseId))
        .as("should be able to update the budget header's record date")
        .startsWith(newRecordalDate);
  }

  @Test
  void addTimeRegistration() {
    final long caseId = FAKER.number().randomNumber();
    final TimeRegistration timeRegistration = TimeRegistration.builder()
        .budgetLineSequenceNumber(FAKER.number().numberBetween(10, 100))
        .caseId(caseId)
        .workCodeId(FAKER.bothify("?#"))
        .userId(FAKER.name().firstName())
        .activityDate(LocalDateTime.now().minusDays(1).format(DATE_TIME_FORMATTER))
        .submissionDate(LocalDateTime.now().format(DATE_TIME_FORMATTER))
        .actualHours(BigDecimal.valueOf(FAKER.number().randomDigitNotZero()))
        .chargeableHours(BigDecimal.valueOf(FAKER.number().randomDigitNotZero()))
        .comment(FAKER.lorem().sentence())
        .build();

    patriciaDao.addTimeRegistration(timeRegistration);

    fluentJdbc.query().select(
        "SELECT work_code_id, case_id, registration_date_time, login_id, calendar_date, worked_time, debited_time, "
            + "   time_transferred, number_of_words, worked_amount, b_l_case_id, time_comment_invoice, time_comment, "
            + "   earliest_invoice_date, b_l_seq_number "
            + " FROM time_registration WHERE case_id = ?")
        .params(caseId)
        .singleResult(rs -> {
          assertThat(rs.getString(1))
              .as("should have save correct work code id")
              .isEqualTo(timeRegistration.workCodeId());
          assertThat(rs.getLong(2))
              .as("should have save correct case id")
              .isEqualTo(timeRegistration.caseId());
          assertThat(rs.getString(3))
              .as("should have save correct submission date")
              .startsWith(timeRegistration.submissionDate());
          assertThat(rs.getString(4))
              .as("should have save correct user id date")
              .isEqualTo(timeRegistration.userId());
          assertThat(rs.getString(5))
              .startsWith(timeRegistration.activityDate());
          assertThat(rs.getBigDecimal(6))
              .as("should have set correct actual hours")
              .isEqualByComparingTo(timeRegistration.actualHours());
          assertThat(rs.getBigDecimal(7))
              .as("should have set corrrect chargeable hours")
              .isEqualByComparingTo(timeRegistration.chargeableHours());
          assertThat(rs.getString(8)).isEqualTo(null);
          assertThat(rs.getString(9)).isEqualTo(null);
          assertThat(rs.getDouble(10)).isEqualTo(0.00);
          assertThat(rs.getLong(11)).isEqualTo(timeRegistration.caseId());
          assertThat(rs.getString(12)).isEqualTo(timeRegistration.comment());
          assertThat(rs.getString(13)).isEqualTo(timeRegistration.comment());
          assertThat(rs.getString(14)).startsWith(timeRegistration.submissionDate());
          assertThat(rs.getInt(15)).isEqualTo(timeRegistration.budgetLineSequenceNumber());

          return Void.TYPE;
        });
  }

  @Test
  void addBudgetLine_chargeingTypeId_null() {
    final long caseId = FAKER.number().randomDigitNotZero();
    final BudgetLine budgetLine = BudgetLine.builder()
        .budgetLineSequenceNumber(FAKER.number().numberBetween(10, 100))
        .caseId(caseId)
        .workCodeId(FAKER.lorem().characters(1, 10))
        // use only first name in tests. Faker full names sometimes get too long for the table
        // in real connector this can't happen because handle comes from the DB
        .userId(FAKER.name().firstName())
        .submissionDate(LocalDateTime.now().format(DATE_TIME_FORMATTER))
        .currency(FAKER.currency().code())
        .hourlyRate(BigDecimal.valueOf(FAKER.number().randomDigitNotZero()))
        .effectiveHourlyRate(BigDecimal.valueOf(FAKER.number().randomDigitNotZero()))
        .actualWorkTotalHours(BigDecimal.valueOf(FAKER.number().randomDigitNotZero()))
        .chargeableWorkTotalHours(BigDecimal.valueOf(FAKER.number().randomDigitNotZero()))
        .actualWorkTotalAmount(BigDecimal.valueOf(FAKER.number().randomDigitNotZero()))
        .chargeableAmount(BigDecimal.valueOf(FAKER.number().randomDigitNotZero()))
        .discountPercentage(BigDecimal.valueOf(FAKER.number().numberBetween(10, 100)))
        .discountAmount(BigDecimal.valueOf(FAKER.number().randomDigitNotZero()))
        .comment(FAKER.lorem().sentence(5))
        .activityDate(LocalDateTime.now().format(DATE_TIME_FORMATTER))
        .chargeTypeId(null)
        .build();

    patriciaDao.addBudgetLine(budgetLine);

    fluentJdbc.query().select(
        "SELECT b_l_seq_number, work_code_id, b_l_quantity, b_l_org_quantity, b_l_unit_price, "
            + "   b_l_org_unit_price, b_l_unit_price_no_discount, deb_handlagg, b_l_amount, b_l_org_amount, case_id,"
            + "   show_time_comment, registered_by, earliest_inv_date, b_l_comment, recorded_date, discount_prec, "
            + "   discount_amount, currency_id, exchange_rate, indicator, EXTERNAL_INVOICE_DATE, CHARGEING_TYPE_ID "
            + " FROM budget_line WHERE case_id = ?")
        .params(caseId)
        .singleResult(rs -> {
          // assert if correct values are set
          assertThat(rs.getInt(1)).isEqualTo(budgetLine.budgetLineSequenceNumber());
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
          assertThat(rs.getString(14)).startsWith(budgetLine.submissionDate());
          assertThat(rs.getString(15)).isEqualTo(budgetLine.comment());
          assertThat(rs.getString(16)).startsWith(budgetLine.submissionDate());
          assertThat(rs.getBigDecimal(17)).isEqualByComparingTo(budgetLine.discountPercentage());
          assertThat(rs.getBigDecimal(18)).isEqualByComparingTo(budgetLine.discountAmount());
          assertThat(rs.getString(19)).isEqualTo(budgetLine.currency());
          assertThat(rs.getInt(20)).isEqualTo(1);
          assertThat(rs.getString(21)).isEqualTo("TT");
          assertThat(rs.getString(22)).startsWith(budgetLine.activityDate());
          assertThat(rs.getObject(23)).isNull();

          return Void.TYPE;
        });

    assertThat(patriciaDao.findNextBudgetLineSeqNum(caseId))
        .as("next sequence number for budget line should be 2")
        .isEqualTo(budgetLine.budgetLineSequenceNumber() + 1);
  }

  @Test
  void addBudgetLine() {
    final long caseId = FAKER.number().randomDigitNotZero();
    final BudgetLine budgetLine = BudgetLine.builder()
        .budgetLineSequenceNumber(FAKER.number().numberBetween(10, 100))
        .caseId(caseId)
        .workCodeId(FAKER.lorem().characters(1, 10))
        .userId(FAKER.name().firstName())
        .submissionDate(LocalDateTime.now().format(DATE_TIME_FORMATTER))
        .currency(FAKER.currency().code())
        .hourlyRate(BigDecimal.valueOf(FAKER.number().randomDigitNotZero()))
        .effectiveHourlyRate(BigDecimal.valueOf(FAKER.number().randomDigitNotZero()))
        .actualWorkTotalHours(BigDecimal.valueOf(FAKER.number().randomDigitNotZero()))
        .chargeableWorkTotalHours(BigDecimal.valueOf(FAKER.number().randomDigitNotZero()))
        .actualWorkTotalAmount(BigDecimal.valueOf(FAKER.number().randomDigitNotZero()))
        .chargeableAmount(BigDecimal.valueOf(FAKER.number().randomDigitNotZero()))
        .discountPercentage(BigDecimal.valueOf(FAKER.number().numberBetween(10, 100)))
        .discountAmount(BigDecimal.valueOf(FAKER.number().randomDigitNotZero()))
        .comment(FAKER.lorem().sentence(5))
        .activityDate(LocalDateTime.now().format(DATE_TIME_FORMATTER))
        .chargeTypeId(FAKER.number().numberBetween(100, 1000))
        .build();

    patriciaDao.addBudgetLine(budgetLine);

    fluentJdbc.query().select(
        "SELECT b_l_seq_number, work_code_id, b_l_quantity, b_l_org_quantity, b_l_unit_price, "
            + "   b_l_org_unit_price, b_l_unit_price_no_discount, deb_handlagg, b_l_amount, b_l_org_amount, case_id,"
            + "   show_time_comment, registered_by, earliest_inv_date, b_l_comment, recorded_date, discount_prec, "
            + "   discount_amount, currency_id, exchange_rate, indicator, EXTERNAL_INVOICE_DATE, CHARGEING_TYPE_ID, "
            + "   P_L_ORG_UNIT_PRICE, P_L_ORG_UNIT_PRICE_NO_DISCOUNT, P_L_ORG_AMOUNT, P_L_ORG_CURRENCY_ID "
            + " FROM budget_line WHERE case_id = ?")
        .params(caseId)
        .singleResult(rs -> {
          // assert if correct values are set
          assertThat(rs.getInt(1)).isEqualTo(budgetLine.budgetLineSequenceNumber());
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
          assertThat(rs.getString(14)).startsWith(budgetLine.submissionDate());
          assertThat(rs.getString(15)).isEqualTo(budgetLine.comment());
          assertThat(rs.getString(16)).startsWith(budgetLine.submissionDate());
          assertThat(rs.getBigDecimal(17)).isEqualByComparingTo(budgetLine.discountPercentage());
          assertThat(rs.getBigDecimal(18)).isEqualByComparingTo(budgetLine.discountAmount());
          assertThat(rs.getString(19)).isEqualTo(budgetLine.currency());
          assertThat(rs.getInt(20)).isEqualTo(1);
          assertThat(rs.getString(21)).isEqualTo("TT");
          assertThat(rs.getString(22)).startsWith(budgetLine.activityDate());
          assertThat(rs.getInt(23)).isEqualTo(budgetLine.chargeTypeId());
          assertThat(rs.getString(24)).isEqualTo(null);
          assertThat(rs.getString(25)).isEqualTo(null);
          assertThat(rs.getString(26)).isEqualTo(null);
          assertThat(rs.getString(27)).isEqualTo(null);

          return Void.TYPE;
        });

    assertThat(patriciaDao.findNextBudgetLineSeqNum(caseId))
        .as("next sequence number for budget line should be 2")
        .isEqualTo(budgetLine.budgetLineSequenceNumber() + 1);
  }

  @Test
  void addBudgetLineFromPriceList() {
    final long caseId = FAKER.number().randomDigitNotZero();
    final BudgetLine budgetLine = BudgetLine.builder()
        .budgetLineSequenceNumber(FAKER.number().numberBetween(10, 100))
        .caseId(caseId)
        .workCodeId(FAKER.lorem().characters(1, 10))
        .userId(FAKER.name().firstName())
        .submissionDate(LocalDateTime.now().format(DATE_TIME_FORMATTER))
        .currency(FAKER.currency().code())
        .hourlyRate(BigDecimal.valueOf(FAKER.number().randomDigitNotZero()))
        .effectiveHourlyRate(BigDecimal.valueOf(FAKER.number().randomDigitNotZero()))
        .actualWorkTotalHours(BigDecimal.valueOf(FAKER.number().randomDigitNotZero()))
        .chargeableWorkTotalHours(BigDecimal.valueOf(FAKER.number().randomDigitNotZero()))
        .actualWorkTotalAmount(BigDecimal.valueOf(FAKER.number().randomDigitNotZero()))
        .chargeableAmount(BigDecimal.valueOf(FAKER.number().randomDigitNotZero()))
        .discountPercentage(BigDecimal.valueOf(FAKER.number().numberBetween(10, 100)))
        .discountAmount(BigDecimal.valueOf(FAKER.number().randomDigitNotZero()))
        .comment(FAKER.lorem().sentence(5))
        .activityDate(LocalDateTime.now().format(DATE_TIME_FORMATTER))
        .chargeTypeId(FAKER.number().numberBetween(100, 1000))
        .build();

    patriciaDao.addBudgetLineFromPriceList(budgetLine);

    fluentJdbc.query().select(
        "SELECT b_l_seq_number, work_code_id, b_l_quantity, b_l_org_quantity, b_l_unit_price, "
            + "   b_l_org_unit_price, b_l_unit_price_no_discount, deb_handlagg, b_l_amount, b_l_org_amount, case_id,"
            + "   show_time_comment, registered_by, earliest_inv_date, b_l_comment, recorded_date, discount_prec, "
            + "   discount_amount, currency_id, exchange_rate, indicator, EXTERNAL_INVOICE_DATE, CHARGEING_TYPE_ID,"
            + "   P_L_ORG_UNIT_PRICE, P_L_ORG_UNIT_PRICE_NO_DISCOUNT, P_L_ORG_AMOUNT, P_L_ORG_CURRENCY_ID "
            + " FROM budget_line WHERE case_id = ?")
        .params(caseId)
        .singleResult(rs -> {
          // assert if correct values are set
          assertThat(rs.getInt(1)).isEqualTo(budgetLine.budgetLineSequenceNumber());
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
          assertThat(rs.getString(14)).startsWith(budgetLine.submissionDate());
          assertThat(rs.getString(15)).isEqualTo(budgetLine.comment());
          assertThat(rs.getString(16)).startsWith(budgetLine.submissionDate());
          assertThat(rs.getBigDecimal(17)).isEqualByComparingTo(budgetLine.discountPercentage());
          assertThat(rs.getBigDecimal(18)).isEqualByComparingTo(budgetLine.discountAmount());
          assertThat(rs.getString(19)).isEqualTo(budgetLine.currency());
          assertThat(rs.getInt(20)).isEqualTo(1);
          assertThat(rs.getString(21)).isEqualTo("TT");
          assertThat(rs.getString(22)).startsWith(budgetLine.activityDate());
          assertThat(rs.getInt(23)).isEqualTo(budgetLine.chargeTypeId());
          assertThat(rs.getBigDecimal(24)).isEqualByComparingTo(budgetLine.effectiveHourlyRate());
          assertThat(rs.getBigDecimal(25)).isEqualByComparingTo(budgetLine.hourlyRate());
          assertThat(rs.getBigDecimal(26)).isEqualByComparingTo(budgetLine.chargeableAmount());
          assertThat(rs.getString(27)).isEqualTo(budgetLine.currency());

          return Void.TYPE;
        });

    assertThat(patriciaDao.findNextBudgetLineSeqNum(caseId))
        .as("next sequence number for budget line should be 2")
        .isEqualTo(budgetLine.budgetLineSequenceNumber() + 1);
  }

  @Test
  void findHourlyRateFromPriceList() {
    int caseCategoryId = FAKER.number().numberBetween(1, 20000);
    int caseCategoryLevel = FAKER.number().numberBetween(1, 20000);
    String currency = FAKER.currency().code();
    double hourlyRate = FAKER.number().randomDigitNotZero();
    Case patriciaCase = RANDOM_DATA_GENERATOR.randomCase();
    int roleTypeId = FAKER.number().numberBetween(1, 1000);
    long caseId = patriciaCase.caseId();
    int actorId = FAKER.number().numberBetween(1, 1000);
    int priceListId = FAKER.number().numberBetween(1, 1000);
    String workCodeId = FAKER.numerify("wc######");
    String loginId = FAKER.numerify("li######");

    saveCase(patriciaCase);
    final LocalDateTime localDateTime = LocalDateTime.now().minusMonths(1);
    createPriceList(actorId, loginId, workCodeId, patriciaCase, currency, hourlyRate,
        priceListId, caseCategoryId, caseCategoryLevel, localDateTime);
    createPriceList(actorId, "^", workCodeId, patriciaCase, currency, 2 * hourlyRate,
        priceListId, caseCategoryId, caseCategoryLevel, localDateTime);
    saveCasting(actorId, caseId, roleTypeId);
    saveRenewalPriceList(priceListId);
    savePatPriceList(priceListId, actorId);

    assertThat(patriciaDao.findHourlyRateFromPriceList(caseId, workCodeId, loginId, roleTypeId).get())
        .extracting("currencyId", "hourlyRate")
        .contains(currency, BigDecimal.valueOf(hourlyRate).setScale(2));
  }

  @Test
  void findHourlyRateFromPriceList_more_recent_price_entry_for_other_user() {
    int caseCategoryId = FAKER.number().numberBetween(1, 20000);
    int caseCategoryLevel = FAKER.number().numberBetween(1, 20000);
    String currency = FAKER.currency().code();
    double hourlyRate = FAKER.number().randomDigitNotZero();
    Case patriciaCase = RANDOM_DATA_GENERATOR.randomCase();
    int roleTypeId = FAKER.number().numberBetween(1, 1000);
    long caseId = patriciaCase.caseId();
    int actorId = FAKER.number().numberBetween(1, 1000);
    int priceListId = FAKER.number().numberBetween(1, 1000);
    String workCodeId = FAKER.numerify("wc######");
    String loginId = FAKER.numerify("li######");

    saveCase(patriciaCase);
    final LocalDateTime timeStampForRelevantPriceAndUser = LocalDateTime.now().minusMonths(2);
    final LocalDateTime newerTimeStampForOtherUser = LocalDateTime.now().minusMonths(1);
    final LocalDateTime futureTimeStampForSameUser = LocalDateTime.now().plusMonths(1);

    createPriceList(actorId, loginId, workCodeId, patriciaCase, currency, hourlyRate,
        priceListId, caseCategoryId, caseCategoryLevel, timeStampForRelevantPriceAndUser);
    createPriceList(actorId, loginId, workCodeId, patriciaCase, currency, hourlyRate + 5,
        priceListId, caseCategoryId, caseCategoryLevel, futureTimeStampForSameUser);
    createPriceList(actorId, "Other_User", workCodeId, patriciaCase, currency, hourlyRate / 2,
        priceListId, caseCategoryId, caseCategoryLevel, newerTimeStampForOtherUser);
    createPriceList(actorId, "^", workCodeId, patriciaCase, currency, 2 * hourlyRate,
        priceListId, caseCategoryId, caseCategoryLevel, timeStampForRelevantPriceAndUser);

    saveCasting(actorId, caseId, roleTypeId);
    savePatPriceList(priceListId, actorId);

    assertThat(patriciaDao.findHourlyRateFromPriceList(caseId, workCodeId, loginId, roleTypeId).get())
        .extracting("currencyId", "hourlyRate")
        .contains(currency, BigDecimal.valueOf(hourlyRate).setScale(2));
  }

  @Test
  void findHourlyRateFromPriceList_fall_back_to_default_price_list() {
    int caseCategoryId = FAKER.number().numberBetween(1, 20000);
    int caseCategoryLevel = FAKER.number().numberBetween(1, 20000);
    String currency = FAKER.currency().code();
    double hourlyRate = FAKER.number().randomDigitNotZero();
    Case patriciaCase = RANDOM_DATA_GENERATOR.randomCase();
    int roleTypeId = FAKER.number().numberBetween(1, 1000);
    long caseId = patriciaCase.caseId();
    int actorId = FAKER.number().numberBetween(1, 1000);
    int priceListId = FAKER.number().numberBetween(1, 1000);
    int defaultPriceListId = FAKER.number().numberBetween(1, 1000);
    String workCodeId = FAKER.numerify("wc######");
    String loginId = FAKER.numerify("li######");

    saveCase(patriciaCase);
    final LocalDateTime timeStamp = LocalDateTime.now().minusMonths(2);
    final LocalDateTime newerTimeStampForOtherUser = LocalDateTime.now().minusMonths(1);

    // default price list and correct user, should be chosen
    createPriceList(actorId, loginId, workCodeId, patriciaCase, currency, hourlyRate,
        defaultPriceListId, caseCategoryId, caseCategoryLevel, timeStamp);
    // correct actor should still take precedence
    createPriceList(0, loginId, workCodeId, patriciaCase, "ABC", hourlyRate + 20,
        defaultPriceListId, caseCategoryId, caseCategoryLevel, timeStamp);
    // correct price list, but wrong user, should not be chosen
    createPriceList(actorId, "Other_User", workCodeId, patriciaCase, "ABC", hourlyRate / 2,
        priceListId, caseCategoryId, caseCategoryLevel, timeStamp);
    // default price list and actor, but generic user, with more recent price entry, should not be chosen
    createPriceList(0, "^", workCodeId, patriciaCase, "DEF", hourlyRate / 3,
        defaultPriceListId, caseCategoryId, caseCategoryLevel, newerTimeStampForOtherUser);

    saveCasting(actorId, caseId, roleTypeId);
    saveRenewalPriceList(defaultPriceListId);
    savePatPriceList(priceListId, actorId);

    assertThat(patriciaDao.findHourlyRateFromPriceList(caseId, workCodeId, loginId, roleTypeId).get())
        .extracting("currencyId", "hourlyRate")
        .contains(currency, BigDecimal.valueOf(hourlyRate).setScale(2));
  }

  @Test
  void findHourlyRateFromPriceList_fall_back_to_default_price_list_zero_actor() {
    int caseCategoryId = FAKER.number().numberBetween(1, 20000);
    int caseCategoryLevel = FAKER.number().numberBetween(1, 20000);
    String currency = FAKER.currency().code();
    double hourlyRate = FAKER.number().randomDigitNotZero();
    Case patriciaCase = RANDOM_DATA_GENERATOR.randomCase();
    int roleTypeId = FAKER.number().numberBetween(1, 1000);
    long caseId = patriciaCase.caseId();
    int actorId = FAKER.number().numberBetween(1, 1000);
    int priceListId = FAKER.number().numberBetween(1, 1000);
    int defaultPriceListId = FAKER.number().numberBetween(1, 1000);
    String workCodeId = FAKER.numerify("wc######");
    String loginId = FAKER.numerify("li######");

    saveCase(patriciaCase);
    final LocalDateTime timeStamp = LocalDateTime.now().minusMonths(2);
    final LocalDateTime newerTimeStampForOtherUser = LocalDateTime.now().minusMonths(1);

    // default price list and correct user, should be chosen
    createPriceList(0, loginId, workCodeId, patriciaCase, currency, hourlyRate,
        defaultPriceListId, caseCategoryId, caseCategoryLevel, timeStamp);
    // correct price list, but wrong user, should not be chosen
    createPriceList(actorId, "Other_User", workCodeId, patriciaCase, "ABC", hourlyRate / 2,
        priceListId, caseCategoryId, caseCategoryLevel, timeStamp);
    // default price list and actor, but generic user, with more recent price entry, should not be chosen
    createPriceList(0, "^", workCodeId, patriciaCase, "DEF", hourlyRate / 3,
        defaultPriceListId, caseCategoryId, caseCategoryLevel, newerTimeStampForOtherUser);

    saveCasting(actorId, caseId, roleTypeId);
    saveRenewalPriceList(defaultPriceListId);
    savePatPriceList(priceListId, actorId);

    assertThat(patriciaDao.findHourlyRateFromPriceList(caseId, workCodeId, loginId, roleTypeId).get())
        .extracting("currencyId", "hourlyRate")
        .contains(currency, BigDecimal.valueOf(hourlyRate).setScale(2));
  }

  @Test
  void findHourlyRateFromPriceList_fall_back_to_default_price_list_default_actor_default_user() {
    int caseCategoryId = FAKER.number().numberBetween(1, 20000);
    int caseCategoryLevel = FAKER.number().numberBetween(1, 20000);
    String currency = FAKER.currency().code();
    double hourlyRate = FAKER.number().randomDigitNotZero();
    Case patriciaCase = RANDOM_DATA_GENERATOR.randomCase();
    int roleTypeId = FAKER.number().numberBetween(1, 1000);
    long caseId = patriciaCase.caseId();
    int actorId = FAKER.number().numberBetween(1, 1000);
    int priceListId = FAKER.number().numberBetween(1, 1000);
    int defaultPriceListId = FAKER.number().numberBetween(1, 1000);
    String workCodeId = FAKER.numerify("wc######");

    saveCase(patriciaCase);
    final LocalDateTime timeStamp = LocalDateTime.now().minusMonths(2);
    final LocalDateTime futureTimeStampForOtherUser = LocalDateTime.now().plusMonths(1);

    // default price list, generic actor and user, should be chosen
    createPriceList(0, "^", workCodeId, patriciaCase, currency, hourlyRate,
        defaultPriceListId, caseCategoryId, caseCategoryLevel, timeStamp);
    // correct price list, correct actor, but wrong user, should not be chosen
    createPriceList(actorId, "Other_User", workCodeId, patriciaCase, "ABC", hourlyRate / 2,
        priceListId, caseCategoryId, caseCategoryLevel, timeStamp);
    // correct price list, generic actor, and default user, should not be chosen
    createPriceList(0, "Other_User2", workCodeId, patriciaCase, "DEF", hourlyRate / 3,
        priceListId, caseCategoryId, caseCategoryLevel, timeStamp);
    // correct price list, actor, and generic user, with future price entry, should not be chosen
    createPriceList(0, "^", workCodeId, patriciaCase, "DEF", hourlyRate * 3,
        priceListId, caseCategoryId, caseCategoryLevel, futureTimeStampForOtherUser);

    saveCasting(actorId, caseId, roleTypeId);
    saveRenewalPriceList(defaultPriceListId);
    savePatPriceList(priceListId, actorId);

    String loginId = FAKER.numerify("li######");
    assertThat(patriciaDao.findHourlyRateFromPriceList(caseId, workCodeId, loginId, roleTypeId).get())
        .extracting("currencyId", "hourlyRate")
        .contains(currency, BigDecimal.valueOf(hourlyRate).setScale(2));
  }

  @Test
  void findHourlyRateFromPriceList_noMatchingActorId() {
    int caseCategoryId = FAKER.number().numberBetween(1, 20000);
    int caseCategoryLevel = FAKER.number().numberBetween(1, 20000);
    String currency = FAKER.currency().code();
    double hourlyRate = FAKER.number().randomDigitNotZero();
    Case patriciaCase = RANDOM_DATA_GENERATOR.randomCase();
    int roleTypeId = FAKER.number().numberBetween(1, 1000);
    long caseId = patriciaCase.caseId();
    int actorId = FAKER.number().numberBetween(1, 1000);
    int priceListId = FAKER.number().numberBetween(1, 1000);
    String workCodeId = FAKER.numerify("wc######");
    String loginId = FAKER.numerify("li######");

    saveCase(patriciaCase);
    saveCasting(2 * actorId, caseId, roleTypeId);
    final LocalDateTime localDateTime = LocalDateTime.now().minusMonths(1);
    createPriceList(actorId, loginId, workCodeId, patriciaCase, currency, 0,
        priceListId, caseCategoryId, caseCategoryLevel, localDateTime);
    createPriceList(actorId, "^", workCodeId, patriciaCase, currency, 0,
        priceListId, caseCategoryId, caseCategoryLevel, localDateTime);
    createPriceList(0, loginId, workCodeId, patriciaCase, currency, hourlyRate,
        priceListId, caseCategoryId, caseCategoryLevel, localDateTime);
    createPriceList(0, "^", workCodeId, patriciaCase, currency, 0,
        priceListId, caseCategoryId, caseCategoryLevel, localDateTime);
    saveRenewalPriceList(priceListId);

    assertThat(patriciaDao.findHourlyRateFromPriceList(caseId, workCodeId, loginId, roleTypeId).get())
        .extracting("currencyId", "hourlyRate")
        .contains(currency, BigDecimal.valueOf(hourlyRate).setScale(2));
  }

  @Test
  void findHourlyRateFromPriceList_noActorId() {
    int caseCategoryId = FAKER.number().numberBetween(1, 20000);
    int caseCategoryLevel = FAKER.number().numberBetween(1, 20000);
    String currency = FAKER.currency().code();
    double hourlyRate = FAKER.number().randomDigitNotZero();
    Case patriciaCase = RANDOM_DATA_GENERATOR.randomCase();
    int roleTypeId = FAKER.number().numberBetween(1, 1000);
    long caseId = patriciaCase.caseId();
    int actorId = FAKER.number().numberBetween(1, 1000);
    int priceListId = FAKER.number().numberBetween(1, 1000);
    String workCodeId = FAKER.numerify("wc######");
    String loginId = FAKER.numerify("li######");

    saveCase(patriciaCase);
    final LocalDateTime localDateTime = LocalDateTime.now().minusMonths(1);
    createPriceList(actorId, loginId, workCodeId, patriciaCase, currency, 0,
        priceListId, caseCategoryId, caseCategoryLevel, localDateTime);
    createPriceList(actorId, "^", workCodeId, patriciaCase, currency, 0,
        priceListId, caseCategoryId, caseCategoryLevel, localDateTime);
    createPriceList(0, loginId, workCodeId, patriciaCase, currency, hourlyRate,
        priceListId, caseCategoryId, caseCategoryLevel, localDateTime);
    createPriceList(0, "^", workCodeId, patriciaCase, currency, 0,
        priceListId, caseCategoryId, caseCategoryLevel, localDateTime);
    saveRenewalPriceList(priceListId);

    assertThat(patriciaDao.findHourlyRateFromPriceList(caseId, workCodeId, loginId, roleTypeId).get())
        .extracting("currencyId", "hourlyRate")
        .contains(currency, BigDecimal.valueOf(hourlyRate).setScale(2));
  }

  @Test
  void findHourlyRateFromPriceList_noPriceListId() {
    int caseCategoryId = FAKER.number().numberBetween(1, 20000);
    int caseCategoryLevel = FAKER.number().numberBetween(1, 20000);
    String currency = FAKER.currency().code();
    double hourlyRate = FAKER.number().randomDigitNotZero();
    Case patriciaCase = RANDOM_DATA_GENERATOR.randomCase();
    int roleTypeId = FAKER.number().numberBetween(1, 1000);
    long caseId = patriciaCase.caseId();
    int actorId = FAKER.number().numberBetween(1, 1000);
    int priceListId = FAKER.number().numberBetween(1, 1000);
    String workCodeId = FAKER.numerify("wc######");
    String loginId = FAKER.numerify("li######");

    saveCase(patriciaCase);
    final LocalDateTime localDateTime = LocalDateTime.now().minusMonths(1);
    createPriceList(actorId, loginId, workCodeId, patriciaCase, currency, 0,
        priceListId, caseCategoryId, caseCategoryLevel, localDateTime);
    createPriceList(actorId, "^", workCodeId, patriciaCase, currency, 0,
        priceListId, caseCategoryId, caseCategoryLevel, localDateTime);
    createPriceList(0, loginId, workCodeId, patriciaCase, currency, hourlyRate,
        priceListId, caseCategoryId, caseCategoryLevel, localDateTime);
    createPriceList(0, "^", workCodeId, patriciaCase, currency, 0,
        priceListId, caseCategoryId, caseCategoryLevel, localDateTime);

    assertThat(patriciaDao.findHourlyRateFromPriceList(caseId, workCodeId, loginId, roleTypeId))
        .isEmpty();
  }

  @Test
  void findHourlyRateFromPriceList_wrongPriceListIdForUser_shouldChooseGenericUser() {
    int caseCategoryId = FAKER.number().numberBetween(1, 20000);
    int caseCategoryLevel = FAKER.number().numberBetween(1, 20000);
    String currency = FAKER.currency().code();
    double hourlyRate = FAKER.number().randomDigitNotZero();
    Case patriciaCase = RANDOM_DATA_GENERATOR.randomCase();
    int roleTypeId = FAKER.number().numberBetween(1, 1000);
    long caseId = patriciaCase.caseId();
    int priceListId = FAKER.number().numberBetween(1, 1000);
    String workCodeId = FAKER.numerify("wc######");
    String loginId = FAKER.numerify("li######");

    saveCase(patriciaCase);
    final LocalDateTime localDateTime = LocalDateTime.now().minusMonths(1);
    createPriceList(0, loginId, workCodeId, patriciaCase, "ABC", 0,
        1, caseCategoryId, caseCategoryLevel, localDateTime);
    createPriceList(0, "^", workCodeId, patriciaCase, "ABC", 0,
        1, caseCategoryId, caseCategoryLevel, localDateTime);
    createPriceList(0, "^", workCodeId, patriciaCase, "ABC", 0,
        priceListId - 1, caseCategoryId, caseCategoryLevel, localDateTime);
    createPriceList(0, loginId, workCodeId, patriciaCase, "ABC", 0,
        priceListId - 1, caseCategoryId, caseCategoryLevel, localDateTime);
    createPriceList(0, "^", workCodeId, patriciaCase, currency, hourlyRate,
        priceListId, caseCategoryId, caseCategoryLevel, localDateTime);
    saveRenewalPriceList(priceListId);

    assertThat(patriciaDao.findHourlyRateFromPriceList(caseId, workCodeId, loginId, roleTypeId).get())
        .extracting("currencyId", "hourlyRate")
        .contains(currency, BigDecimal.valueOf(hourlyRate).setScale(2));;
  }

  @Test
  void findHourlyRateFromPriceList_genericLoginId() {
    int caseCategoryId = FAKER.number().numberBetween(1, 20000);
    int caseCategoryLevel = FAKER.number().numberBetween(1, 20000);
    String currency = FAKER.currency().code();
    double hourlyRate = FAKER.number().randomDigitNotZero();
    Case patriciaCase = RANDOM_DATA_GENERATOR.randomCase();
    int roleTypeId = FAKER.number().numberBetween(1, 1000);
    long caseId = patriciaCase.caseId();
    int actorId = FAKER.number().numberBetween(1, 1000);
    int priceListId = FAKER.number().numberBetween(1, 1000);
    String workCodeId = FAKER.numerify("wc######");
    String loginId = FAKER.numerify("li######");

    saveCase(patriciaCase);
    createPriceList(actorId, loginId, workCodeId, patriciaCase, "WRO", hourlyRate * 2,
        priceListId, caseCategoryId, caseCategoryLevel, LocalDateTime.now().plusMonths(1));
    createPriceList(actorId, "otherUser", workCodeId, patriciaCase, "OWO", hourlyRate + 5,
        priceListId, caseCategoryId, caseCategoryLevel, LocalDateTime.now().minusMonths(1));
    createPriceList(actorId, "^", workCodeId, patriciaCase, currency, hourlyRate,
        priceListId, caseCategoryId, caseCategoryLevel, LocalDateTime.now().minusMonths(2));
    saveCasting(actorId, caseId, roleTypeId);
    saveRenewalPriceList(priceListId);
    savePatPriceList(priceListId, actorId);

    assertThat(patriciaDao.findHourlyRateFromPriceList(caseId, workCodeId, loginId, roleTypeId).get())
        .extracting("currencyId", "hourlyRate")
        .contains(currency, BigDecimal.valueOf(hourlyRate).setScale(2));
  }

  @Test
  void findHourlyRateFromPriceList_wrongPriceList() {
    int caseCategoryId = FAKER.number().numberBetween(1, 20000);
    int caseCategoryLevel = FAKER.number().numberBetween(1, 20000);
    String currency = FAKER.currency().code();
    double hourlyRate = FAKER.number().randomDigitNotZero();
    Case patriciaCase = RANDOM_DATA_GENERATOR.randomCase();
    int roleTypeId = FAKER.number().numberBetween(1, 1000);
    long caseId = patriciaCase.caseId();
    int actorId = FAKER.number().numberBetween(1, 1000);
    int priceListId = FAKER.number().numberBetween(1, 1000);
    String workCodeId = FAKER.numerify("wc######");
    String loginId = FAKER.numerify("li######");

    saveCase(patriciaCase);
    createPriceList(actorId, loginId, workCodeId, patriciaCase, currency, hourlyRate,
        0, caseCategoryId, caseCategoryLevel, LocalDateTime.now().minusMonths(1));
    saveCasting(actorId, caseId, roleTypeId);
    saveRenewalPriceList(priceListId);
    savePatPriceList(priceListId, actorId);

    assertThat(patriciaDao.findHourlyRateFromPriceList(caseId, workCodeId, loginId, roleTypeId)).isEmpty();
  }

  @Test
  void findWorkCodes() {
    final int languageIdEng = FAKER.number().numberBetween(1, 1000);
    final int languageIdFr = languageIdEng + 1;
    createLanguage(languageIdEng, "English");
    createLanguage(languageIdFr, "French");

    // create active work code with `T` type with English and French translations
    final String workCodeId1 = FAKER.numerify("wc######");
    final String workCodeTextFr = FAKER.numerify("text-fr-######");
    final String workCodeTextEng = FAKER.numerify("text-eng-######");
    createWorkCode(workCodeId1, "T", true);
    createWorkCodeText(workCodeId1, workCodeTextEng, languageIdEng);
    createWorkCodeText(workCodeId1, workCodeTextFr, languageIdFr);

    // create active work code with wrong type with English and French translations
    final String workCodeId2 = FAKER.numerify("wc######");
    createWorkCode(workCodeId2, "A", true);
    createWorkCodeText(workCodeId2, FAKER.numerify("text-eng-######"), languageIdEng);
    createWorkCodeText(workCodeId2, FAKER.numerify("text-fr-######"), languageIdFr);

    // create NON-active work code with `T` type with English and French translations
    final String workCodeId3 = FAKER.numerify("wc######");
    createWorkCode(workCodeId3, "T", false);
    createWorkCodeText(workCodeId3, FAKER.numerify("text-eng-######"), languageIdEng);
    createWorkCodeText(workCodeId3, FAKER.numerify("text-fr-######"), languageIdFr);

    // check that active work code with type `T` and default `English` translation is returned
    RuntimeConfig.clearProperty(PatriciaConnectorConfigKey.PATRICIA_LANGUAGE);
    assertThat(patriciaDao.findWorkCodes(0, 10))
        .as("English translation should be retrieved by default")
        .containsExactly(WorkCode.builder()
            .workCodeId(workCodeId1)
            .workCodeText(workCodeTextEng)
            .build());

    // check that active work code with type `T` and configured language is returned
    RuntimeConfig.setProperty(PatriciaConnectorConfigKey.PATRICIA_LANGUAGE, "French");
    assertThat(patriciaDao.findWorkCodes(0, 10))
        .as("French translation should be retrieved as configured")
        .containsExactly(WorkCode.builder()
            .workCodeId(workCodeId1)
            .workCodeText(workCodeTextFr)
            .build());
  }

  @Test
  void findWorkCodes_offset_limit() {
    final int languageIdEng = FAKER.number().numberBetween(1, 1000);
    final int languageIdFr = languageIdEng + 1;
    createLanguage(languageIdEng, "English");
    createLanguage(languageIdFr, "French");

    // create work code 1 (should be skipped by offset)
    final String workCodeId1 = FAKER.numerify("wc1-######");
    final String workCodeTextFr1 = FAKER.numerify("text-fr-######");
    final String workCodeTextEng1 = FAKER.numerify("text-eng-######");
    createWorkCode(workCodeId1, "T", true);
    createWorkCodeText(workCodeId1, workCodeTextEng1, languageIdEng);
    createWorkCodeText(workCodeId1, workCodeTextFr1, languageIdFr);

    // create work code 2 (should be returned)
    final String workCodeId2 = FAKER.numerify("wc2-######");
    final String workCodeTextFr2 = FAKER.numerify("text-fr-######");
    final String workCodeTextEng2 = FAKER.numerify("text-eng-######");
    createWorkCode(workCodeId2, "T", true);
    createWorkCodeText(workCodeId2, workCodeTextEng2, languageIdEng);
    createWorkCodeText(workCodeId2, workCodeTextFr2, languageIdFr);

    // create work code 2 (should be skipped by limit)
    final String workCodeId3 = FAKER.numerify("wc3-######");
    final String workCodeTextFr3 = FAKER.numerify("text-fr-######");
    final String workCodeTextEng3 = FAKER.numerify("text-eng-######");
    createWorkCode(workCodeId3, "T", true);
    createWorkCodeText(workCodeId3, workCodeTextEng3, languageIdEng);
    createWorkCodeText(workCodeId3, workCodeTextFr3, languageIdFr);

    // check that proper work code with configured language is returned
    RuntimeConfig.setProperty(PatriciaConnectorConfigKey.PATRICIA_LANGUAGE, "French");
    assertThat(patriciaDao.findWorkCodes(1, 1))
        .as("French translation should be retrieved as configured")
        .containsExactly(WorkCode.builder()
            .workCodeId(workCodeId2)
            .workCodeText(workCodeTextFr2)
            .build());
  }

  private void saveCase(Case patCase) {
    fluentJdbc.query()
        .update("INSERT INTO vw_case_number (case_id, case_number) VALUES (?, ?)")
        .params(patCase.caseId(), patCase.caseNumber())
        .run();

    fluentJdbc.query()
        .update("INSERT INTO pat_case (case_id, case_catch_word, state_id, application_type_id, case_type_id) "
            + "VALUES (?, ?, ?, ?, ?)")
        .params(patCase.caseId(), patCase.caseCatchWord(), patCase.stateId(), patCase.appId(), patCase.caseTypeId())
        .run();
  }

  private void saveRenewalPriceList(int priceListId) {
    fluentJdbc.query().update("INSERT INTO RENEWAL_PRICE_LIST (PRICE_LIST_ID, DEFAULT_PRICE_LIST) VALUES (?, ?)")
        .params(priceListId, 1)
        .run();
  }

  private void savePatPriceList(int priceListId, int actorId) {
    fluentJdbc.query().update("INSERT INTO pat_names (PRICE_LIST_ID, NAME_ID) VALUES (?, ?)")
        .params(priceListId, actorId)
        .run();
  }

  private void savePerson(String loginId, String email, double hourlyRate) {
    fluentJdbc.query().update("INSERT INTO person (login_id, email, hourly_rate) VALUES (?, ?, ?)")
        .params(loginId, email, hourlyRate)
        .run();
  }

  private void savePersonWorkCodeRate(String loginId, String workCode, String currency, double hourlyRate,
      Long caseId) {
    fluentJdbc.query().update(
        "INSERT INTO pat_person_hourly_rate "
            + "(pat_person_hourly_rate_id, currency, login_id, work_code_id, hourly_rate) "
            + " VALUES (?, ?, ?, ?, ?)")
        .params(FAKER.number().randomDigit(), currency, loginId, workCode, hourlyRate)
        .run();
    if (caseId != null) {
      int roleTypeId = FAKER.number().numberBetween(1, 1000000);
      int actorId = FAKER.number().numberBetween(1, 1000000);
      saveCasting(actorId, caseId, roleTypeId);
      fluentJdbc.query().update(
              "INSERT INTO pat_names_entity "
                  + "(ENTITY_ID, name_id) "
                  + " VALUES (?, ?)")
          .params(FAKER.number().randomDigit(), actorId)
          .run();
    }
  }

  private void saveDefaultWorkCodeRate(String workCode, double hourlyRate, int replaceAmount) {
    fluentJdbc.query().update(
        "INSERT INTO work_code (work_code_id, work_code_default_amount, replace_amount) "
            + " VALUES (?, ?, ?)")
        .params(workCode, hourlyRate, replaceAmount)
        .run();
  }

  private void saveRandomDiscount(DiscountPriority discountPriority,
      int discountId,
      int userId,
      String workCode) {
    fluentJdbc.query().update("INSERT INTO pat_work_code_discount_header (discount_id, actor_id, case_type_id, "
        + " state_id, application_type_id, work_code_type, work_code_id, discount_type) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
        .params(
            discountId,
            userId,
            discountPriority.isHasCaseTypeId() ? FAKER.number().numberBetween(1, 100) : null,
            discountPriority.isHasStateId() ? FAKER.bothify("?#") : null,
            discountPriority.isHasAppTypeId() ? FAKER.number().numberBetween(1, 100) : null,
            discountPriority.getWorkCodeType(),
            discountPriority.isHasWorkCodeId() ? workCode : null,
            FAKER.number().numberBetween(1, 2)
        )
        .run();

    fluentJdbc.query().update("INSERT INTO pat_work_code_discount_detail (discount_id, amount, price_change_formula) "
        + " VALUES (?, ?, ?)")
        .params(discountId, FAKER.number().randomDigit(), "@")
        .run();

  }

  private void saveCasting(int actorId, long caseId, int roleTypeId) {
    fluentJdbc.query().update(
        "INSERT INTO casting (actor_id, case_id, role_type_id, case_role_sequence) VALUES (?, ?, ?, ?)")
        .params(actorId, caseId, roleTypeId, 1)
        .run();
  }

  @SuppressWarnings("ParameterNumber")
  private void createPriceList(int actorId, String loginId, String workCodeId, Case patCase, String currency,
      double hourlyRate, int priceListId, int caseCategoryId, int caseCategoryLevel,
      LocalDateTime localDateTime) {
    int caseMasterId = FAKER.number().numberBetween(1, 20000);
    fluentJdbc.query().update(
        "INSERT INTO chargeing_price_list (CASE_CATEGORY_ID, STATUS_ID, WORK_CODE_ID, PRICE_CHANGE_DATE, ACTOR_ID, "
            + "PRICE_LIST_ID, login_id, currency_id, PRICE) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")
        .params(caseCategoryId, 1, workCodeId, localDateTime, actorId, priceListId,
            loginId, currency, hourlyRate)
        .run();
    fluentJdbc.query().update(
        "INSERT INTO case_category (case_master_id, case_type_id, state_id, case_category_id, case_category_level) "
            + "VALUES (?, ?, ?, ?, ?)")
        .params(caseMasterId, patCase.caseTypeId(), patCase.stateId(), caseCategoryId, caseCategoryLevel)
        .run();
    fluentJdbc.query().update(
        "INSERT INTO case_type_definition (CASE_TYPE_ID, case_master_id) "
            + "VALUES (?, ?)")
        .params(patCase.caseTypeId(), caseMasterId)
        .run();
    fluentJdbc.query().update(
        "INSERT INTO CASE_TYPE_DEFAULT_STATE (CASE_TYPE_ID, STATE_ID) "
            + "VALUES (?, ?)")
        .params(caseMasterId, patCase.stateId())
        .run();
  }

  private void createWorkCode(String workCodeId, String workCodeType, boolean active) {
    fluentJdbc.query().update(
        "INSERT INTO WORK_CODE (WORK_CODE_ID, WORK_CODE_TYPE, IS_ACTIVE) VALUES (?, ?, ?)")
        .params(workCodeId, workCodeType, active)
        .run();
  }

  private void createWorkCodeText(String workCodeId, String workCodeText, int languageId) {
    fluentJdbc.query().update(
        "INSERT INTO WORK_CODE_TEXT (WORK_CODE_ID, LANGUAGE_ID, WORK_CODE_TEXT) VALUES (?, ?, ?)")
        .params(workCodeId, languageId, workCodeText)
        .run();
  }

  private void createLanguage(int languageId, String language) {
    fluentJdbc.query().update(
        "INSERT INTO LANGUAGE_CODE (LANGUAGE_ID, LANGUAGE_LABEL) VALUES (?, ?)")
        .params(languageId, language)
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
      private Provider<HikariDataSource> dataSourceProvider;

      @Override
      public Flyway get() {
        return new Flyway(new FluentConfiguration()
            .dataSource(dataSourceProvider.get())
            .baselineVersion(MigrationVersion.fromVersion("0"))
            .baselineOnMigrate(true)
            .locations("patricia_db_schema/"));
      }
    }
  }
}

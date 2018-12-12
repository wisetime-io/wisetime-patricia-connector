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
import org.codejargon.fluentjdbc.api.query.Query;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.sql.DataSource;

import io.wisetime.connector.config.RuntimeConfig;

import static io.wisetime.connector.patricia.ConnectorLauncher.PatriciaConnectorConfigKey;
import static io.wisetime.connector.patricia.ConnectorLauncher.PatriciaDbModule;
import static io.wisetime.connector.patricia.PatriciaDao.Case;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author alvin.llobrera@practiceinsight.io
 */
class PatriciaDaoTest {

  private static final String TEST_JDBC_URL = "jdbc:h2:mem:test_patricia_db;DB_CLOSE_DELAY=-1";
  private static final RandomDataGenerator RANDOM_DATA_GENERATOR = new RandomDataGenerator();
  private static final Faker FAKER = new Faker();
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
    fluentJdbc.query().update("INSERT INTO person (login_id, email) VALUES (?, ?)")
        .params("foobar", "foobar@baz.com")
        .run();

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
    int caseId = FAKER.number().randomDigit();
    int roleTypeId = FAKER.number().randomDigit();
    int actorId = FAKER.number().randomDigit();
    String currency = FAKER.currency().code();

    fluentJdbc.query().update(
        "INSERT INTO casting (actor_id, case_id, role_type_id, case_role_sequence) VALUES (?, ?, ?, ?)")
        .params(actorId, caseId, roleTypeId, 1)
        .run();
    fluentJdbc.query().update("INSERT INTO pat_names (name_id, currency_id) VALUES (?, ?)")
        .params(actorId, currency)
        .run();

    assertThat(patriciaDao.findCurrency(caseId, roleTypeId))
        .as("should be able to retrieve currency defined for a case")
        .contains(currency);
    assertThat(patriciaDao.findCurrency(FAKER.number().randomDigit(), roleTypeId))
        .as("no defined currency for that case id")
        .isEmpty();
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

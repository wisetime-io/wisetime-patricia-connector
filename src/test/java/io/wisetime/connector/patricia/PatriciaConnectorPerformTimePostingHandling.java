/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.patricia;

import com.google.common.collect.ImmutableList;
import com.google.inject.Guice;

import com.github.javafaker.Faker;

import java.io.IOException;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import io.wisetime.connector.ConnectorModule;
import io.wisetime.connector.api_client.ApiClient;
import io.wisetime.connector.api_client.PostResult;
import io.wisetime.connector.api_client.PostResult.PostResultStatus;
import io.wisetime.connector.config.RuntimeConfig;
import io.wisetime.connector.datastore.ConnectorStore;
import io.wisetime.generated.connect.DeleteTagRequest;
import io.wisetime.generated.connect.Tag;
import io.wisetime.generated.connect.TimeGroup;
import io.wisetime.generated.connect.TimeRow;
import io.wisetime.generated.connect.User;
import spark.Request;

import static io.wisetime.connector.patricia.PatriciaDao.BudgetLine;
import static io.wisetime.connector.patricia.PatriciaDao.Case;
import static io.wisetime.connector.patricia.PatriciaDao.TimeRegistration;
import static io.wisetime.connector.patricia.ConnectorLauncher.PatriciaConnectorConfigKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

/**
 * @author vadym
 */
class PatriciaConnectorPerformTimePostingHandling {

  private static final Faker FAKER = new Faker();
  private static final FakeEntities FAKE_ENTITIES = new FakeEntities();
  private static final String TAG_UPSERT_PATH = "/Patricia/";

  private static final String ZERO_CHARGE_WORK_CODES = " zero1 ,zero2 ,";
  private static final String ACTIVITY_TYPE_CODE = "DM";

  private static RandomDataGenerator randomDataGenerator = new RandomDataGenerator();
  private static PatriciaDao patriciaDaoMock = mock(PatriciaDao.class);
  private static ApiClient apiClientMock = mock(ApiClient.class);
  private static ConnectorStore connectorStoreMock = mock(ConnectorStore.class);
  private static PatriciaConnector connector;

  @BeforeAll
  static void setUp() {
    RuntimeConfig.rebuild();
    RuntimeConfig.setProperty(ConnectorLauncher.PatriciaConnectorConfigKey.TAG_UPSERT_PATH, TAG_UPSERT_PATH);
    RuntimeConfig.setProperty(ConnectorLauncher.PatriciaConnectorConfigKey.WORK_CODES_ZERO_CHARGE, ZERO_CHARGE_WORK_CODES);
    RuntimeConfig.setProperty(ConnectorLauncher.PatriciaConnectorConfigKey.TIMEZONE, "Asia/Manila");

    // Set a role type id to use
    RuntimeConfig.setProperty(ConnectorLauncher.PatriciaConnectorConfigKey.PATRICIA_ROLE_TYPE_ID, "4");

    connector = Guice.createInjector(
        binder -> binder.bind(PatriciaDao.class).toProvider(() -> patriciaDaoMock)
    )
        .getInstance(PatriciaConnector.class);

    // Ensure PatriciaConnector#init will not fail
    doReturn(true).when(patriciaDaoMock).hasExpectedSchema();

    connector.init(new ConnectorModule(apiClientMock, connectorStoreMock, 5, 15));
  }

  @BeforeEach
  void setUpTest() {
    reset(patriciaDaoMock);
    reset(apiClientMock);
    reset(connectorStoreMock);

    // Ensure that code in the transaction lambda gets exercised
    doAnswer(invocation -> {
      ((Runnable) invocation.getArgument(0)).run();
      return null;
    }).when(patriciaDaoMock).asTransaction(any());
  }

  @AfterEach
  void cleanup() {
    RuntimeConfig.clearProperty(ConnectorLauncher.PatriciaConnectorConfigKey.INVOICE_COMMENT_OVERRIDE);
  }

  @Test
  void postTime_noTags() {
    TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup();
    timeGroup.setTags(Collections.emptyList());

    assertThat(connector.postTime(fakeRequest(), timeGroup))
        .usingRecursiveComparison()
        .as("no tags in time group")
        .isEqualTo(PostResult.SUCCESS().withMessage("Time group has no tags. There is nothing to post to Patricia."));

    verifyZeroInteractions(patriciaDaoMock);
  }

  @Test
  void postTime_tag_not_exists_in_patricia_db() throws IOException {
    Tag tag = FAKE_ENTITIES.randomTag(TAG_UPSERT_PATH, "tag_not_exists");
    TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup()
        .tags(ImmutableList.of(tag));
    when(patriciaDaoMock.findCaseByCaseNumber(tag.getName()))
        .thenReturn(Optional.empty());

    String userLogin = FAKER.internet().uuid();
    String dbDate = LocalDateTime.now().toString();
    String currency = FAKER.currency().code();
    BigDecimal hourlyRate = BigDecimal.TEN;

    when(patriciaDaoMock.findLoginIdByEmail(timeGroup.getUser().getExternalId())).thenReturn(Optional.of(userLogin));
    when(patriciaDaoMock.findPatPersonHourlyRate(any(), eq(userLogin))).thenReturn(Optional.of(hourlyRate));
    when(patriciaDaoMock.getDbDate()).thenReturn(dbDate);
    when(patriciaDaoMock.findCurrency(anyLong(), anyInt())).thenReturn(Optional.of(currency));

    assertThat(connector.postTime(fakeRequest(), timeGroup).getStatus())
        .as("tag not found in db")
        .isEqualTo(PostResult.PERMANENT_FAILURE().getStatus());

    for (Tag t : timeGroup.getTags()) {
      verify(apiClientMock, times(1)).tagDelete(new DeleteTagRequest().name(t.getName()));
    }
  }

  @Test
  void postTime_tag_non_patricia_tag() {
    Tag tag = FAKE_ENTITIES.randomTag("/NonPatricia/", "tag");
    TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup()
        .tags(ImmutableList.of(tag));

    String userLogin = FAKER.internet().uuid();
    String dbDate = LocalDateTime.now().toString();
    String currency = FAKER.currency().code();
    BigDecimal hourlyRate = BigDecimal.TEN;

    when(patriciaDaoMock.findLoginIdByEmail(timeGroup.getUser().getExternalId())).thenReturn(Optional.of(userLogin));
    when(patriciaDaoMock.findPatPersonHourlyRate(any(), eq(userLogin))).thenReturn(Optional.of(hourlyRate));
    when(patriciaDaoMock.getDbDate()).thenReturn(dbDate);
    when(patriciaDaoMock.findCurrency(anyLong(), anyInt())).thenReturn(Optional.of(currency));

    assertThat(connector.postTime(fakeRequest(), timeGroup).getStatus())
        .as("tag is not Patricia tag")
        .isEqualTo(PostResult.SUCCESS().getStatus());
  }

  @Test
  void postTime_noTimeRows() {
    TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup();
    timeGroup.setTimeRows(Collections.emptyList());
    timeGroup.getTags()
        .forEach(tag -> tag.setPath(RuntimeConfig.getString(PatriciaConnectorConfigKey.TAG_UPSERT_PATH).get()));

    assertThat(connector.postTime(fakeRequest(), timeGroup))
        .usingRecursiveComparison()
        .as("no time rows in time group")
        .isEqualTo(PostResult.PERMANENT_FAILURE().withMessage("Cannot post time group with no time rows"));

    verifyZeroInteractions(patriciaDaoMock);
  }

  @Test
  void postTime_externalIdNotEmail_cantFindUser() {
    final String externalId = "i.am.login.id";
    final TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup(ACTIVITY_TYPE_CODE)
        .user(FAKE_ENTITIES.randomUser()
            .externalId(externalId));
    timeGroup.getTags()
        .forEach(tag -> tag.setPath(RuntimeConfig.getString(PatriciaConnectorConfigKey.TAG_UPSERT_PATH).get()));

    when(patriciaDaoMock.loginIdExists(externalId)).thenReturn(false);

    PostResult result = connector.postTime(fakeRequest(), timeGroup);
    assertThat(result.getStatus())
        .as("Can't post time because external id is not a valid Patricia login ID")
        .isEqualTo(PostResultStatus.PERMANENT_FAILURE);
    assertThat(result.getMessage())
        .as("should be the correct error message for invalid user")
        .contains("User does not exist: " + timeGroup.getUser().getExternalId());

    verify(patriciaDaoMock, never()).findLoginIdByEmail(anyString());
    verifyPatriciaNotUpdated();
  }

  @Test
  void postTime_externalIdAsEmail_cantFindUser() {
    final String externalId = "this-looks@like.email";
    final TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup(ACTIVITY_TYPE_CODE)
        .user(FAKE_ENTITIES.randomUser()
            .externalId(externalId));
    timeGroup.getTags()
        .forEach(tag -> tag.setPath(RuntimeConfig.getString(PatriciaConnectorConfigKey.TAG_UPSERT_PATH).get()));

    when(patriciaDaoMock.loginIdExists(externalId)).thenReturn(false);
    when(patriciaDaoMock.findLoginIdByEmail(externalId)).thenReturn(Optional.empty());

    PostResult result = connector.postTime(fakeRequest(), timeGroup);
    assertThat(result.getStatus())
        .as("Can't post time because external id is not a valid Patricia login ID or email")
        .isEqualTo(PostResultStatus.PERMANENT_FAILURE);
    assertThat(result.getMessage())
        .as("should be the correct error message for invalid user")
        .contains("User does not exist: " + timeGroup.getUser().getExternalId());

    verifyPatriciaNotUpdated();
  }

  @Test
  void postTime_noExternalId_cantFindUserByUserEmail() {
    final TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup(ACTIVITY_TYPE_CODE)
        .user(FAKE_ENTITIES.randomUser()
            .externalId(null)); // we should only check on email if external id is not set
    timeGroup.getTags()
        .forEach(tag -> tag.setPath(RuntimeConfig.getString(PatriciaConnectorConfigKey.TAG_UPSERT_PATH).get()));

    when(patriciaDaoMock.findLoginIdByEmail(timeGroup.getUser().getEmail())).thenReturn(Optional.empty());

    PostResult result = connector.postTime(fakeRequest(), timeGroup);
    assertThat(result.getStatus())
        .as("Can't post time because no user has this email in Patricia")
        .isEqualTo(PostResultStatus.PERMANENT_FAILURE);
    assertThat(result.getMessage())
        .as("should be the correct error message for invalid user")
        .contains("User does not exist: " + timeGroup.getUser().getExternalId());

    verify(patriciaDaoMock, never()).loginIdExists(anyString());
    verifyPatriciaNotUpdated();
  }

  @Test
  void postTime_noHourlyRate() {
    final Tag tag = FAKE_ENTITIES.randomTag(TAG_UPSERT_PATH, "tag");
    final TimeRow timeRow = FAKE_ENTITIES.randomTimeRow()
        .activityTypeCode(ACTIVITY_TYPE_CODE)
        .activityHour(2018110110)
        .firstObservedInHour(0);
    timeRow.setDescription(FAKER.lorem().characters());
    final User user = FAKE_ENTITIES.randomUser().experienceWeightingPercent(50);

    final TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup()
        .tags(ImmutableList.of(tag))
        .timeRows(ImmutableList.of(timeRow))
        .user(user)
        .totalDurationSecs(1500);

    final Case patriciaCase = randomDataGenerator.randomCase();
    String currency = FAKER.currency().code();

    String userLogin = FAKER.internet().uuid();
    when(patriciaDaoMock.findLoginIdByEmail(timeGroup.getUser().getExternalId())).thenReturn(Optional.of(userLogin));
    when(patriciaDaoMock.findCaseByCaseNumber(tag.getName())).thenReturn(Optional.of(patriciaCase));
    when(patriciaDaoMock.getDbDate()).thenReturn(LocalDateTime.now().toString());
    when(patriciaDaoMock.findCurrency(anyLong(), anyInt())).thenReturn(Optional.of(currency));

    PostResult postResult = connector.postTime(fakeRequest(), timeGroup);
    assertThat(postResult.getStatus())
        .as("unable to find hourly rate")
        .isEqualTo(PostResultStatus.PERMANENT_FAILURE);
    assertThat(postResult.getMessage())
        .as("result should contain a descriptive message of the failure")
        .contains("No hourly rate is found for " + userLogin);

    verifyPatriciaNotUpdated();
  }


  @Test
  void postTime_unable_to_get_date_from_db() {
    final Tag tag = FAKE_ENTITIES.randomTag(TAG_UPSERT_PATH, "tag");
    final TimeRow timeRow = FAKE_ENTITIES.randomTimeRow().activityTypeCode(ACTIVITY_TYPE_CODE).activityHour(2018110110);
    timeRow.setDescription(FAKER.lorem().characters());
    final User user = FAKE_ENTITIES.randomUser().experienceWeightingPercent(50);

    final TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup()
        .tags(ImmutableList.of(tag))
        .timeRows(ImmutableList.of(timeRow))
        .user(user)
        .totalDurationSecs(1500);

    String userLogin = FAKER.internet().uuid();
    when(patriciaDaoMock.findLoginIdByEmail(timeGroup.getUser().getExternalId())).thenReturn(Optional.of(userLogin));
    when(patriciaDaoMock.findPatPersonHourlyRate(any(), eq(userLogin))).thenReturn(Optional.of(BigDecimal.TEN));
    when(patriciaDaoMock.findCaseByCaseNumber(tag.getName())).thenReturn(Optional.of(randomDataGenerator.randomCase()));
    when(patriciaDaoMock.getDbDate()).thenThrow(new NoSuchElementException("No value present"));

    assertThat(connector.postTime(fakeRequest(), timeGroup).getStatus())
        .as("failed to load database date")
        .isEqualTo(PostResultStatus.TRANSIENT_FAILURE);

    verifyPatriciaNotUpdated();
  }

  @Test
  void postTime_unable_to_get_currency() {
    final Tag tag = FAKE_ENTITIES.randomTag(TAG_UPSERT_PATH, "tag");
    final TimeRow timeRow = FAKE_ENTITIES.randomTimeRow()
        .activityTypeCode(ACTIVITY_TYPE_CODE)
        .activityHour(2018110110)
        .firstObservedInHour(0);
    timeRow.setDescription(FAKER.lorem().characters());
    final User user = FAKE_ENTITIES.randomUser().experienceWeightingPercent(50);

    final TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup()
        .tags(ImmutableList.of(tag))
        .timeRows(ImmutableList.of(timeRow))
        .user(user)
        .totalDurationSecs(1500);

    final Case patriciaCase = randomDataGenerator.randomCase();

    String userLogin = FAKER.internet().uuid();
    when(patriciaDaoMock.findLoginIdByEmail(timeGroup.getUser().getExternalId())).thenReturn(Optional.of(userLogin));
    when(patriciaDaoMock.findPatPersonHourlyRate(any(), eq(userLogin))).thenReturn(Optional.of(BigDecimal.TEN));
    when(patriciaDaoMock.findCaseByCaseNumber(tag.getName())).thenReturn(Optional.of(patriciaCase));
    when(patriciaDaoMock.getDbDate()).thenReturn(LocalDateTime.now().toString());

    PostResult postResult = connector.postTime(fakeRequest(), timeGroup);
    assertThat(postResult.getStatus())
        .as("unable to find currency for the case")
        .isEqualTo(PostResultStatus.PERMANENT_FAILURE);
    assertThat(postResult.getMessage())
        .as("result should contain a descriptive message of the failure")
        .contains("Could not find currency for the case " + patriciaCase.caseNumber()
                + ". Please make sure an account address is configured for this case in the 'Parties' tab.");

    verifyPatriciaNotUpdated();
  }

  @Test
  void postTime_multiple_activityTypeCode() {
    final Tag tag = FAKE_ENTITIES.randomTag(TAG_UPSERT_PATH, "tag");
    final TimeRow timeRow1 = FAKE_ENTITIES.randomTimeRow()
        .activityTypeCode(ACTIVITY_TYPE_CODE).activityHour(2018110110);
    final TimeRow timeRow2 = FAKE_ENTITIES.randomTimeRow()
        .activityTypeCode("another activity typ code").activityHour(2018110110);
    final User user = FAKE_ENTITIES.randomUser().experienceWeightingPercent(50);

    final TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup()
        .tags(ImmutableList.of(tag))
        .timeRows(ImmutableList.of(timeRow1, timeRow2))
        .user(user)
        .totalDurationSecs(1500);

    assertThat(connector.postTime(fakeRequest(), timeGroup).getStatus())
        .as("Time group contains multiple activity type codes.")
        .isEqualTo(PostResultStatus.PERMANENT_FAILURE);

    verifyPatriciaNotUpdated();
  }

  @Test
  @SuppressWarnings({"MethodLength", "ExecutableStatementCount"})
  void postTime() {
    final int chargeTypeId = FAKER.number().numberBetween(100, 1000);
    RuntimeConfig.setProperty(ConnectorLauncher.PatriciaConnectorConfigKey.WT_CHARGE_TYPE_ID, chargeTypeId + "");
    final Tag tag1 = FAKE_ENTITIES.randomTag(TAG_UPSERT_PATH, "tag1");

    final TimeRow timeRow1 = FAKE_ENTITIES.randomTimeRow()
        .activityHour(2018110121)
        .activityTypeCode(ACTIVITY_TYPE_CODE)
        .durationSecs(600)
        .firstObservedInHour(0);
    timeRow1.setDescription(FAKER.lorem().characters());
    final TimeRow timeRow2 = FAKE_ENTITIES.randomTimeRow()
        .activityHour(2018110122)
        .activityTypeCode(ACTIVITY_TYPE_CODE)
        .durationSecs(300)
        .firstObservedInHour(0);
    timeRow2.setDescription(FAKER.lorem().characters());

    final User user = FAKE_ENTITIES.randomUser().experienceWeightingPercent(50);

    final TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup()
        .tags(ImmutableList.of(tag1))
        .timeRows(ImmutableList.of(timeRow1, timeRow2))
        .user(user)
        .totalDurationSecs(900);

    RuntimeConfig.setProperty(ConnectorLauncher.PatriciaConnectorConfigKey.INVOICE_COMMENT_OVERRIDE, "custom_comment");

    final Case patriciaCase1 = randomDataGenerator.randomCase(tag1.getName());

    when(patriciaDaoMock.findCaseByCaseNumber(anyString()))
        .thenReturn(Optional.of(patriciaCase1));

    String userLogin = FAKER.internet().uuid();
    String dbDate = LocalDateTime.now().toString();
    String currency = FAKER.currency().code();
    BigDecimal hourlyRate = BigDecimal.TEN;

    when(patriciaDaoMock.findLoginIdByEmail(timeGroup.getUser().getExternalId())).thenReturn(Optional.of(userLogin));
    when(patriciaDaoMock.findPatPersonHourlyRate(any(), eq(userLogin))).thenReturn(Optional.of(hourlyRate));
    when(patriciaDaoMock.getDbDate()).thenReturn(dbDate);
    when(patriciaDaoMock.findCurrency(anyLong(), anyInt())).thenReturn(Optional.of(currency));

    assertThat(connector.postTime(fakeRequest(), timeGroup).getStatus())
        .as("Valid time group should be posted successfully")
        .isEqualTo(PostResultStatus.SUCCESS);

    // Verify Time Registration creation
    ArgumentCaptor<TimeRegistration> timeRegCaptor = ArgumentCaptor.forClass(TimeRegistration.class);
    verify(patriciaDaoMock, times(1)).addTimeRegistration(timeRegCaptor.capture());
    List<TimeRegistration> timeRegistrations = timeRegCaptor.getAllValues();

    assertThat(timeRegistrations.get(0).caseId())
        .as("time registration should have correct case id")
        .isEqualTo(patriciaCase1.caseId());
    assertThat(timeRegistrations.get(0).workCodeId())
        .as("should use default work code")
        .isEqualTo("DM");
    assertThat(timeRegistrations.get(0).submissionDate())
        .as("submission date should equal to the current DB date")
        .isEqualTo(dbDate);
    assertThat(timeRegistrations.get(0).activityDate())
        .as("activity date should equal to the activity date of the row in user time zone")
        .isEqualTo("2018-11-02");
    assertThat(timeRegistrations.get(0).actualHours())
        .as("actual hours should corresponds to the total rows duration, disregarding user experience")
        .isEqualTo(BigDecimal.valueOf(0.25));
    assertThat(timeRegistrations.get(0).chargeableHours())
        .as("chargeable hours should corresponds to the group duration, using user experience")
        .isEqualByComparingTo(BigDecimal.valueOf(.13));
    assertThat(timeRegistrations.get(0).comment())
        .as("should use the value of `INVOICE_COMMENT_OVERRIDE` env variable when specified")
        .isEqualTo("custom_comment");

    // Verify Budget Line creation
    ArgumentCaptor<BudgetLine> budgetLineCaptor = ArgumentCaptor.forClass(BudgetLine.class);
    verify(patriciaDaoMock, times(1)).addBudgetLine(budgetLineCaptor.capture());
    List<BudgetLine> budgetLines = budgetLineCaptor.getAllValues();

    assertThat(budgetLines.get(0).caseId())
        .as("budget line should have correct case id")
        .isEqualTo(patriciaCase1.caseId());
    assertThat(budgetLines.get(0).workCodeId())
        .as("should default to default work code")
        .isEqualTo("DM");
    assertThat(budgetLines.get(0).activityDate())
        .as("activity date should equal to the activity date of the row in user time zone")
        .isEqualTo("2018-11-02");
    assertThat(budgetLines.get(0).submissionDate())
        .as("submission date should equal to the current DB date")
        .isEqualTo(dbDate);
    assertThat(budgetLines.get(0).currency())
        .as("currency should be set")
        .isEqualTo(currency);
    assertThat(budgetLines.get(0).hourlyRate())
        .as("currency should be set")
        .isEqualTo(hourlyRate);
    assertThat(budgetLines.get(0).actualWorkTotalAmount())
        .as("hourly rate * actual hours (applying experience rating)")
        .isEqualByComparingTo(BigDecimal.valueOf(1.3));
    assertThat(budgetLines.get(0).chargeableAmount())
        .as("hourly rate * chargeable hours (applying experience rating and discounts)")
        .isEqualByComparingTo(BigDecimal.valueOf(1.3));
    assertThat(budgetLines.get(0).comment())
        .as("should use the value of `INVOICE_COMMENT_OVERRIDE` env variable when specified")
        .isEqualTo("custom_comment");
    assertThat(budgetLines.get(0).chargeTypeId())
        .as("should use the value of `WT_CHARGE_TYPE_ID` env variable when specified")
        .isEqualTo(chargeTypeId);

    verify(patriciaDaoMock, never()).getSystemDefaultCurrency();
    RuntimeConfig.clearProperty(ConnectorLauncher.PatriciaConnectorConfigKey.WT_CHARGE_TYPE_ID);
  }

  @Test
  @SuppressWarnings({"MethodLength", "ExecutableStatementCount"})
  void postTime_workCodeHourlyRate() {
    final int chargeTypeId = FAKER.number().numberBetween(100, 1000);
    RuntimeConfig.setProperty(ConnectorLauncher.PatriciaConnectorConfigKey.WT_CHARGE_TYPE_ID, chargeTypeId + "");
    final Tag tag1 = FAKE_ENTITIES.randomTag(TAG_UPSERT_PATH, "tag1");

    final TimeRow timeRow1 = FAKE_ENTITIES.randomTimeRow()
        .activityHour(2018110121)
        .activityTypeCode(ACTIVITY_TYPE_CODE)
        .durationSecs(720)
        .firstObservedInHour(0);
    timeRow1.setDescription(FAKER.lorem().characters());

    final User user = FAKE_ENTITIES.randomUser().experienceWeightingPercent(50);

    final TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup()
        .tags(ImmutableList.of(tag1))
        .timeRows(ImmutableList.of(timeRow1))
        .user(user)
        .totalDurationSecs(720);

    RuntimeConfig.setProperty(ConnectorLauncher.PatriciaConnectorConfigKey.INVOICE_COMMENT_OVERRIDE, "custom_comment");

    final Case patriciaCase1 = randomDataGenerator.randomCase(tag1.getName());

    when(patriciaDaoMock.findCaseByCaseNumber(anyString()))
        .thenReturn(Optional.of(patriciaCase1));

    String userLogin = FAKER.internet().uuid();
    String dbDate = LocalDateTime.now().toString();
    String currency = FAKER.currency().code();
    BigDecimal hourlyRate = BigDecimal.TEN;

    when(patriciaDaoMock.findLoginIdByEmail(timeGroup.getUser().getExternalId())).thenReturn(Optional.of(userLogin));
    when(patriciaDaoMock.findWorkCodeDefaultHourlyRate(eq(ACTIVITY_TYPE_CODE))).thenReturn(Optional.of(hourlyRate));
    when(patriciaDaoMock.findPatPersonHourlyRate(any(), eq(userLogin)))
        .thenReturn(Optional.of(hourlyRate.multiply(BigDecimal.valueOf(2))));
    when(patriciaDaoMock.getDbDate()).thenReturn(dbDate);
    when(patriciaDaoMock.findCurrency(anyLong(), anyInt())).thenReturn(Optional.of(currency));

    assertThat(connector.postTime(fakeRequest(), timeGroup).getStatus())
        .as("Valid time group should be posted successfully")
        .isEqualTo(PostResultStatus.SUCCESS);

    // Verify Budget Line creation
    ArgumentCaptor<BudgetLine> budgetLineCaptor = ArgumentCaptor.forClass(BudgetLine.class);
    verify(patriciaDaoMock, times(1)).addBudgetLine(budgetLineCaptor.capture());
    List<BudgetLine> budgetLines = budgetLineCaptor.getAllValues();

    assertThat(budgetLines.get(0).caseId())
        .as("budget line should have correct case id")
        .isEqualTo(patriciaCase1.caseId());
    assertThat(budgetLines.get(0).workCodeId())
        .as("should default to default work code")
        .isEqualTo("DM");
    assertThat(budgetLines.get(0).activityDate())
        .as("activity date should equal to the activity date of the row in user time zone")
        .isEqualTo("2018-11-02");
    assertThat(budgetLines.get(0).submissionDate())
        .as("submission date should equal to the current DB date")
        .isEqualTo(dbDate);
    assertThat(budgetLines.get(0).currency())
        .as("currency should be set")
        .isEqualTo(currency);
    assertThat(budgetLines.get(0).hourlyRate())
        .as("currency should be set")
        .isEqualTo(hourlyRate);
    assertThat(budgetLines.get(0).actualWorkTotalAmount())
        .as("hourly rate * actual hours (applying experience rating)")
        .isEqualByComparingTo(BigDecimal.valueOf(1.0));
    assertThat(budgetLines.get(0).chargeableAmount())
        .as("hourly rate * chargeable hours (applying experience rating and discounts)")
        .isEqualByComparingTo(BigDecimal.valueOf(1.0));
    assertThat(budgetLines.get(0).comment())
        .as("should use the value of `INVOICE_COMMENT_OVERRIDE` env variable when specified")
        .isEqualTo("custom_comment");
    assertThat(budgetLines.get(0).chargeTypeId())
        .as("should use the value of `WT_CHARGE_TYPE_ID` env variable when specified")
        .isEqualTo(chargeTypeId);

    verify(patriciaDaoMock, never()).getSystemDefaultCurrency();
    RuntimeConfig.clearProperty(ConnectorLauncher.PatriciaConnectorConfigKey.WT_CHARGE_TYPE_ID);
  }

  @Test
  @SuppressWarnings({"MethodLength", "ExecutableStatementCount"})
  void postTime_priceListHourlyRateAndCurrency() {
    final int chargeTypeId = FAKER.number().numberBetween(100, 1000);
    RuntimeConfig.setProperty(ConnectorLauncher.PatriciaConnectorConfigKey.WT_CHARGE_TYPE_ID, chargeTypeId + "");
    final Tag tag1 = FAKE_ENTITIES.randomTag(TAG_UPSERT_PATH, "tag1");

    final TimeRow timeRow1 = FAKE_ENTITIES.randomTimeRow()
        .activityHour(2018110121)
        .activityTypeCode(ACTIVITY_TYPE_CODE)
        .durationSecs(720)
        .firstObservedInHour(0);
    timeRow1.setDescription(FAKER.lorem().characters());

    final User user = FAKE_ENTITIES.randomUser().experienceWeightingPercent(50);

    final TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup()
        .tags(ImmutableList.of(tag1))
        .timeRows(ImmutableList.of(timeRow1))
        .user(user)
        .totalDurationSecs(720);

    RuntimeConfig.setProperty(ConnectorLauncher.PatriciaConnectorConfigKey.INVOICE_COMMENT_OVERRIDE, "custom_comment");

    final Case patriciaCase1 = randomDataGenerator.randomCase(tag1.getName());

    when(patriciaDaoMock.findCaseByCaseNumber(anyString()))
        .thenReturn(Optional.of(patriciaCase1));

    String userLogin = FAKER.internet().uuid();
    String dbDate = LocalDateTime.now().toString();
    String currency = FAKER.currency().code();
    BigDecimal hourlyRate = BigDecimal.TEN;

    when(patriciaDaoMock.findLoginIdByEmail(timeGroup.getUser().getExternalId())).thenReturn(Optional.of(userLogin));
    when(patriciaDaoMock.findHourlyRateFromPriceList(patriciaCase1.caseId(), ACTIVITY_TYPE_CODE, userLogin, 4))
        .thenReturn(Optional.of(ImmutablePriceListEntry.builder()
            .workCodeId(ACTIVITY_TYPE_CODE)
            .priceListId(1)
            .loginId(userLogin)
            .hourlyRate(hourlyRate)
            .currencyId(currency)
            .actorId(1)
            .caseId(patriciaCase1.caseId())
            .build()));
    when(patriciaDaoMock.findPatPersonHourlyRate(any(), eq(userLogin)))
        .thenReturn(Optional.of(hourlyRate.multiply(BigDecimal.valueOf(2))));
    when(patriciaDaoMock.getDbDate()).thenReturn(dbDate);
    when(patriciaDaoMock.findCurrency(anyLong(), anyInt())).thenReturn(Optional.empty());

    assertThat(connector.postTime(fakeRequest(), timeGroup).getStatus())
        .as("Valid time group should be posted successfully")
        .isEqualTo(PostResultStatus.SUCCESS);

    // Verify Budget Line creation
    ArgumentCaptor<BudgetLine> budgetLineCaptor = ArgumentCaptor.forClass(BudgetLine.class);
    verify(patriciaDaoMock, times(1)).addBudgetLine(budgetLineCaptor.capture());
    List<BudgetLine> budgetLines = budgetLineCaptor.getAllValues();

    assertThat(budgetLines.get(0).caseId())
        .as("budget line should have correct case id")
        .isEqualTo(patriciaCase1.caseId());
    assertThat(budgetLines.get(0).workCodeId())
        .as("should default to default work code")
        .isEqualTo("DM");
    assertThat(budgetLines.get(0).activityDate())
        .as("activity date should equal to the activity date of the row in user time zone")
        .isEqualTo("2018-11-02");
    assertThat(budgetLines.get(0).submissionDate())
        .as("submission date should equal to the current DB date")
        .isEqualTo(dbDate);
    assertThat(budgetLines.get(0).currency())
        .as("currency should be set")
        .isEqualTo(currency);
    assertThat(budgetLines.get(0).hourlyRate())
        .as("currency should be set")
        .isEqualTo(hourlyRate);
    assertThat(budgetLines.get(0).actualWorkTotalAmount())
        .as("hourly rate * actual hours (applying experience rating)")
        .isEqualByComparingTo(BigDecimal.valueOf(1.0));
    assertThat(budgetLines.get(0).chargeableAmount())
        .as("hourly rate * chargeable hours (applying experience rating and discounts)")
        .isEqualByComparingTo(BigDecimal.valueOf(1.0));
    assertThat(budgetLines.get(0).comment())
        .as("should use the value of `INVOICE_COMMENT_OVERRIDE` env variable when specified")
        .isEqualTo("custom_comment");
    assertThat(budgetLines.get(0).chargeTypeId())
        .as("should use the value of `WT_CHARGE_TYPE_ID` env variable when specified")
        .isEqualTo(chargeTypeId);

    verify(patriciaDaoMock, never()).getSystemDefaultCurrency();
    RuntimeConfig.clearProperty(ConnectorLauncher.PatriciaConnectorConfigKey.WT_CHARGE_TYPE_ID);
  }

  @Test
  @SuppressWarnings({"MethodLength", "ExecutableStatementCount"})
  void postTime_personDefaultHourlyRate() {
    final int chargeTypeId = FAKER.number().numberBetween(100, 1000);
    RuntimeConfig.setProperty(ConnectorLauncher.PatriciaConnectorConfigKey.WT_CHARGE_TYPE_ID, chargeTypeId + "");
    final Tag tag1 = FAKE_ENTITIES.randomTag(TAG_UPSERT_PATH, "tag1");

    final TimeRow timeRow1 = FAKE_ENTITIES.randomTimeRow()
        .activityHour(2018110121)
        .activityTypeCode(ACTIVITY_TYPE_CODE)
        .durationSecs(720)
        .firstObservedInHour(0);
    timeRow1.setDescription(FAKER.lorem().characters());

    final User user = FAKE_ENTITIES.randomUser().experienceWeightingPercent(50);

    final TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup()
        .tags(ImmutableList.of(tag1))
        .timeRows(ImmutableList.of(timeRow1))
        .user(user)
        .totalDurationSecs(720);

    RuntimeConfig.setProperty(ConnectorLauncher.PatriciaConnectorConfigKey.INVOICE_COMMENT_OVERRIDE, "custom_comment");

    final Case patriciaCase1 = randomDataGenerator.randomCase(tag1.getName());

    when(patriciaDaoMock.findCaseByCaseNumber(anyString()))
        .thenReturn(Optional.of(patriciaCase1));

    String userLogin = FAKER.internet().uuid();
    String dbDate = LocalDateTime.now().toString();
    String currency = FAKER.currency().code();
    BigDecimal hourlyRate = BigDecimal.TEN;

    when(patriciaDaoMock.findLoginIdByEmail(timeGroup.getUser().getExternalId())).thenReturn(Optional.of(userLogin));
    when(patriciaDaoMock.findPersonDefaultHourlyRate(eq(userLogin))).thenReturn(Optional.of(hourlyRate));
    when(patriciaDaoMock.getDbDate()).thenReturn(dbDate);
    when(patriciaDaoMock.findCurrency(anyLong(), anyInt())).thenReturn(Optional.of(currency));

    assertThat(connector.postTime(fakeRequest(), timeGroup).getStatus())
        .as("Valid time group should be posted successfully")
        .isEqualTo(PostResultStatus.SUCCESS);

    // Verify Budget Line creation
    ArgumentCaptor<BudgetLine> budgetLineCaptor = ArgumentCaptor.forClass(BudgetLine.class);
    verify(patriciaDaoMock, times(1)).addBudgetLine(budgetLineCaptor.capture());
    List<BudgetLine> budgetLines = budgetLineCaptor.getAllValues();

    assertThat(budgetLines.get(0).caseId())
        .as("budget line should have correct case id")
        .isEqualTo(patriciaCase1.caseId());
    assertThat(budgetLines.get(0).workCodeId())
        .as("should default to default work code")
        .isEqualTo("DM");
    assertThat(budgetLines.get(0).activityDate())
        .as("activity date should equal to the activity date of the row in user time zone")
        .isEqualTo("2018-11-02");
    assertThat(budgetLines.get(0).submissionDate())
        .as("submission date should equal to the current DB date")
        .isEqualTo(dbDate);
    assertThat(budgetLines.get(0).currency())
        .as("currency should be set")
        .isEqualTo(currency);
    assertThat(budgetLines.get(0).hourlyRate())
        .as("currency should be set")
        .isEqualTo(hourlyRate);
    assertThat(budgetLines.get(0).actualWorkTotalAmount())
        .as("hourly rate * actual hours (applying experience rating)")
        .isEqualByComparingTo(BigDecimal.valueOf(1.0));
    assertThat(budgetLines.get(0).chargeableAmount())
        .as("hourly rate * chargeable hours (applying experience rating and discounts)")
        .isEqualByComparingTo(BigDecimal.valueOf(1.0));
    assertThat(budgetLines.get(0).comment())
        .as("should use the value of `INVOICE_COMMENT_OVERRIDE` env variable when specified")
        .isEqualTo("custom_comment");
    assertThat(budgetLines.get(0).chargeTypeId())
        .as("should use the value of `WT_CHARGE_TYPE_ID` env variable when specified")
        .isEqualTo(chargeTypeId);

    verify(patriciaDaoMock, never()).getSystemDefaultCurrency();
    RuntimeConfig.clearProperty(ConnectorLauncher.PatriciaConnectorConfigKey.WT_CHARGE_TYPE_ID);
  }


  @Test
  @SuppressWarnings({"MethodLength", "ExecutableStatementCount"})
  void postTime_FallbackCurrency() {
    String currency = FAKER.currency().code();
    RuntimeConfig.setProperty(PatriciaConnectorConfigKey.FALLBACK_CURRENCY, currency);
    final int chargeTypeId = FAKER.number().numberBetween(100, 1000);
    RuntimeConfig.setProperty(ConnectorLauncher.PatriciaConnectorConfigKey.WT_CHARGE_TYPE_ID, chargeTypeId + "");
    final Tag tag1 = FAKE_ENTITIES.randomTag(TAG_UPSERT_PATH, "tag1");

    final TimeRow timeRow1 = FAKE_ENTITIES.randomTimeRow()
        .activityHour(2018110121)
        .activityTypeCode(ACTIVITY_TYPE_CODE)
        .durationSecs(600)
        .firstObservedInHour(0);
    timeRow1.setDescription(FAKER.lorem().characters());
    final TimeRow timeRow2 = FAKE_ENTITIES.randomTimeRow()
        .activityHour(2018110122)
        .activityTypeCode(ACTIVITY_TYPE_CODE)
        .durationSecs(300)
        .firstObservedInHour(0);
    timeRow2.setDescription(FAKER.lorem().characters());

    final User user = FAKE_ENTITIES.randomUser().experienceWeightingPercent(50);

    final TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup()
        .tags(ImmutableList.of(tag1))
        .timeRows(ImmutableList.of(timeRow1, timeRow2))
        .user(user)
        .totalDurationSecs(900);

    RuntimeConfig.setProperty(ConnectorLauncher.PatriciaConnectorConfigKey.INVOICE_COMMENT_OVERRIDE, "custom_comment");

    final Case patriciaCase1 = randomDataGenerator.randomCase(tag1.getName());

    when(patriciaDaoMock.findCaseByCaseNumber(anyString()))
        .thenReturn(Optional.of(patriciaCase1));

    String userLogin = FAKER.internet().uuid();
    String dbDate = LocalDateTime.now().toString();
    BigDecimal hourlyRate = BigDecimal.TEN;

    when(patriciaDaoMock.findLoginIdByEmail(timeGroup.getUser().getExternalId())).thenReturn(Optional.of(userLogin));
    when(patriciaDaoMock.findPatPersonHourlyRate(any(), eq(userLogin))).thenReturn(Optional.of(hourlyRate));
    when(patriciaDaoMock.getDbDate()).thenReturn(dbDate);
    when(patriciaDaoMock.findCurrency(anyLong(), anyInt())).thenReturn(Optional.empty());

    assertThat(connector.postTime(fakeRequest(), timeGroup).getStatus())
        .as("Valid time group should be posted successfully")
        .isEqualTo(PostResultStatus.SUCCESS);

    // Verify Time Registration creation
    ArgumentCaptor<TimeRegistration> timeRegCaptor = ArgumentCaptor.forClass(TimeRegistration.class);
    verify(patriciaDaoMock, times(1)).addTimeRegistration(timeRegCaptor.capture());
    List<TimeRegistration> timeRegistrations = timeRegCaptor.getAllValues();

    assertThat(timeRegistrations.get(0).caseId())
        .as("time registration should have correct case id")
        .isEqualTo(patriciaCase1.caseId());
    assertThat(timeRegistrations.get(0).workCodeId())
        .as("should use default work code")
        .isEqualTo("DM");
    assertThat(timeRegistrations.get(0).submissionDate())
        .as("submission date should equal to the current DB date")
        .isEqualTo(dbDate);
    assertThat(timeRegistrations.get(0).activityDate())
        .as("activity date should equal to the activity date of the row in user time zone")
        .isEqualTo("2018-11-02");
    assertThat(timeRegistrations.get(0).actualHours())
        .as("actual hours should corresponds to the total rows duration, disregarding user experience")
        .isEqualTo(BigDecimal.valueOf(0.25));
    assertThat(timeRegistrations.get(0).chargeableHours())
        .as("chargeable hours should corresponds to the group duration, using user experience and")
        .isEqualByComparingTo(BigDecimal.valueOf(.13));
    assertThat(timeRegistrations.get(0).comment())
        .as("should use the value of `INVOICE_COMMENT_OVERRIDE` env variable when specified")
        .isEqualTo("custom_comment");

    // Verify Budget Line creation
    ArgumentCaptor<BudgetLine> budgetLineCaptor = ArgumentCaptor.forClass(BudgetLine.class);
    verify(patriciaDaoMock, times(1)).addBudgetLine(budgetLineCaptor.capture());
    List<BudgetLine> budgetLines = budgetLineCaptor.getAllValues();

    assertThat(budgetLines.get(0).caseId())
        .as("budget line should have correct case id")
        .isEqualTo(patriciaCase1.caseId());
    assertThat(budgetLines.get(0).workCodeId())
        .as("should default to default work code")
        .isEqualTo("DM");
    assertThat(budgetLines.get(0).activityDate())
        .as("activity date should equal to the activity date of the row in user time zone")
        .isEqualTo("2018-11-02");
    assertThat(budgetLines.get(0).submissionDate())
        .as("submission date should equal to the current DB date")
        .isEqualTo(dbDate);
    assertThat(budgetLines.get(0).currency())
        .as("currency should be set")
        .isEqualTo(currency);
    assertThat(budgetLines.get(0).hourlyRate())
        .as("currency should be set")
        .isEqualTo(hourlyRate);
    assertThat(budgetLines.get(0).actualWorkTotalAmount())
        .as("hourly rate * actual hours (applying experience rating)")
        .isEqualByComparingTo(BigDecimal.valueOf(1.3));
    assertThat(budgetLines.get(0).chargeableAmount())
        .as("hourly rate * chargeable hours (applying experience rating and discounts)")
        .isEqualByComparingTo(BigDecimal.valueOf(1.3));
    assertThat(budgetLines.get(0).comment())
        .as("should use the value of `INVOICE_COMMENT_OVERRIDE` env variable when specified")
        .isEqualTo("custom_comment");
    assertThat(budgetLines.get(0).chargeTypeId())
        .as("should use the value of `WT_CHARGE_TYPE_ID` env variable when specified")
        .isEqualTo(chargeTypeId);

    verify(patriciaDaoMock, never()).getSystemDefaultCurrency();
    RuntimeConfig.clearProperty(ConnectorLauncher.PatriciaConnectorConfigKey.WT_CHARGE_TYPE_ID);
    RuntimeConfig.clearProperty(PatriciaConnectorConfigKey.FALLBACK_CURRENCY);
  }

  @Test
  void postTime_editedTotalDuration() {
    final Tag tag1 = FAKE_ENTITIES.randomTag(TAG_UPSERT_PATH, "tag1");

    final TimeRow timeRow1 = FAKE_ENTITIES.randomTimeRow()
        .activityHour(2018110121)
        .activityTypeCode(ACTIVITY_TYPE_CODE)
        .durationSecs(600)
        .firstObservedInHour(0)
        .description(FAKER.lorem().characters());
    final TimeRow timeRow2 = FAKE_ENTITIES.randomTimeRow()
        .activityHour(2018110122)
        .activityTypeCode(ACTIVITY_TYPE_CODE)
        .durationSecs(300)
        .firstObservedInHour(0)
        .description(FAKER.lorem().characters());

    final User user = FAKE_ENTITIES.randomUser().experienceWeightingPercent(50);

    final TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup()
        .tags(ImmutableList.of(tag1))
        .timeRows(ImmutableList.of(timeRow1, timeRow2))
        .user(user)

        .totalDurationSecs(1500);

    RuntimeConfig.setProperty(ConnectorLauncher.PatriciaConnectorConfigKey.INVOICE_COMMENT_OVERRIDE, "custom_comment");

    final Case patriciaCase1 = randomDataGenerator.randomCase(tag1.getName());

    when(patriciaDaoMock.findCaseByCaseNumber(anyString()))
        .thenReturn(Optional.of(patriciaCase1));

    String userLogin = FAKER.internet().uuid();
    String dbDate = LocalDateTime.now().toString();
    String currency = FAKER.currency().code();
    BigDecimal hourlyRate = BigDecimal.TEN;

    when(patriciaDaoMock.findLoginIdByEmail(timeGroup.getUser().getExternalId())).thenReturn(Optional.of(userLogin));
    when(patriciaDaoMock.findPatPersonHourlyRate(any(), eq(userLogin))).thenReturn(Optional.of(hourlyRate));
    when(patriciaDaoMock.getDbDate()).thenReturn(dbDate);
    when(patriciaDaoMock.findCurrency(anyLong(), anyInt())).thenReturn(Optional.of(currency));

    assertThat(connector.postTime(fakeRequest(), timeGroup).getStatus())
        .as("Valid time group should be posted successfully")
        .isEqualTo(PostResultStatus.SUCCESS);

    // Verify Time Registration creation
    ArgumentCaptor<TimeRegistration> timeRegCaptor = ArgumentCaptor.forClass(TimeRegistration.class);
    verify(patriciaDaoMock, times(1)).addTimeRegistration(timeRegCaptor.capture());
    List<TimeRegistration> timeRegistrations = timeRegCaptor.getAllValues();

    assertThat(timeRegistrations.get(0).caseId())
        .as("time registration should have correct case id")
        .isEqualTo(patriciaCase1.caseId());
    assertThat(timeRegistrations.get(0).workCodeId())
        .as("should use default work code")
        .isEqualTo("DM");
    assertThat(timeRegistrations.get(0).submissionDate())
        .as("submission date should equal to the current DB date")
        .isEqualTo(dbDate);
    assertThat(timeRegistrations.get(0).activityDate())
        .as("activity date should equal to the activity date of the row in user time zone")
        .isEqualTo("2018-11-02");
    assertThat(timeRegistrations.get(0).actualHours())
        .as("actual hours should corresponds to the total rows duration, disregarding user experience and")
        .isEqualTo(BigDecimal.valueOf(0.25));
    assertThat(timeRegistrations.get(0).chargeableHours())
        .as("chargeable hours should corresponds to the group duration, without user experience " +
            "if the total duration was edited")
        .isEqualByComparingTo(BigDecimal.valueOf(.42));
    assertThat(timeRegistrations.get(0).comment())
        .as("should use the value of `INVOICE_COMMENT_OVERRIDE` env variable when specified")
        .isEqualTo("custom_comment");

    // Verify Budget Line creation
    ArgumentCaptor<BudgetLine> budgetLineCaptor = ArgumentCaptor.forClass(BudgetLine.class);
    verify(patriciaDaoMock, times(1)).addBudgetLine(budgetLineCaptor.capture());
    List<BudgetLine> budgetLines = budgetLineCaptor.getAllValues();

    assertThat(budgetLines.get(0).caseId())
        .as("budget line should have correct case id")
        .isEqualTo(patriciaCase1.caseId());
    assertThat(budgetLines.get(0).workCodeId())
        .as("should default to default work code")
        .isEqualTo("DM");
    assertThat(budgetLines.get(0).submissionDate())
        .as("submission date should equal to the current DB date")
        .isEqualTo(dbDate);
    assertThat(budgetLines.get(0).currency())
        .as("currency should be set")
        .isEqualTo(currency);
    assertThat(budgetLines.get(0).hourlyRate())
        .as("currency should be set")
        .isEqualTo(hourlyRate);
    assertThat(budgetLines.get(0).actualWorkTotalAmount())
        .as("hourly rate * actual hours (without experience rating, if total duration was edited)")
        .isEqualByComparingTo(BigDecimal.valueOf(4.2));
    assertThat(budgetLines.get(0).chargeableAmount())
        .as("hourly rate * chargeable hours " +
            "(without experience rating, if total duration was edited and discounts)")
        .isEqualByComparingTo(BigDecimal.valueOf(4.2));
    assertThat(budgetLines.get(0).comment())
        .as("should use the value of `INVOICE_COMMENT_OVERRIDE` env variable when specified")
        .isEqualTo("custom_comment");
    assertThat(budgetLines.get(0).chargeTypeId())
        .as("should use null when env variable `WT_CHARGE_TYPE_ID` is not specified")
        .isNull();
  }

  @Test
  @SuppressWarnings("MethodLength")
  void postTime_zeroChargeWorkCode() {
    final Tag tag1 = FAKE_ENTITIES.randomTag(TAG_UPSERT_PATH, "tag1");

    final TimeRow timeRow1 = FAKE_ENTITIES.randomTimeRow().activityHour(2018110121)
        .activityTypeCode("zero1")
        .durationSecs(600)
        .firstObservedInHour(0)
        .description(FAKER.lorem().characters());
    final TimeRow timeRow2 = FAKE_ENTITIES.randomTimeRow()
        .activityHour(2018110122)
        .activityTypeCode("zero1")
        .durationSecs(300)
        .firstObservedInHour(0)
        .description(FAKER.lorem().characters());

    final User user = FAKE_ENTITIES.randomUser().experienceWeightingPercent(50);

    final TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup()
        .tags(ImmutableList.of(tag1))
        .timeRows(ImmutableList.of(timeRow1, timeRow2))
        .user(user)
        .totalDurationSecs(900);

    RuntimeConfig.setProperty(ConnectorLauncher.PatriciaConnectorConfigKey.INVOICE_COMMENT_OVERRIDE, "custom_comment");

    final Case patriciaCase1 = randomDataGenerator.randomCase(tag1.getName());

    when(patriciaDaoMock.findCaseByCaseNumber(anyString()))
        .thenReturn(Optional.of(patriciaCase1));

    String userLogin = FAKER.internet().uuid();
    String dbDate = LocalDateTime.now().toString();
    String currency = FAKER.currency().code();
    BigDecimal hourlyRate = BigDecimal.TEN;

    when(patriciaDaoMock.findLoginIdByEmail(timeGroup.getUser().getExternalId())).thenReturn(Optional.of(userLogin));
    when(patriciaDaoMock.findPatPersonHourlyRate(any(), eq(userLogin))).thenReturn(Optional.of(hourlyRate));
    when(patriciaDaoMock.getDbDate()).thenReturn(dbDate);
    when(patriciaDaoMock.findCurrency(anyLong(), anyInt())).thenReturn(Optional.of(currency));

    assertThat(connector.postTime(fakeRequest(), timeGroup).getStatus())
        .as("Valid time group should be posted successfully")
        .isEqualTo(PostResultStatus.SUCCESS);

    // Verify Time Registration creation
    ArgumentCaptor<TimeRegistration> timeRegCaptor = ArgumentCaptor.forClass(TimeRegistration.class);
    verify(patriciaDaoMock, times(1)).addTimeRegistration(timeRegCaptor.capture());
    List<TimeRegistration> timeRegistrations = timeRegCaptor.getAllValues();

    assertThat(timeRegistrations.get(0).caseId())
        .as("time registration should have correct case id")
        .isEqualTo(patriciaCase1.caseId());
    assertThat(timeRegistrations.get(0).workCodeId())
        .as("should use zero amount work code")
        .isEqualTo("zero1");
    assertThat(timeRegistrations.get(0).submissionDate())
        .as("submission date should equal to the current DB date")
        .isEqualTo(dbDate);
    assertThat(timeRegistrations.get(0).activityDate())
        .as("activity date should equal to the activity date of the row in user time zone")
        .isEqualTo("2018-11-02");
    assertThat(timeRegistrations.get(0).actualHours())
        .as("actual hours should corresponds to the total rows duration, disregarding user experience")
        .isEqualTo(BigDecimal.valueOf(0.25));
    assertThat(timeRegistrations.get(0).chargeableHours())
        .as("chargeable hours should be 0 for zero charge work codes")
        .isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(timeRegistrations.get(0).comment())
        .as("should use the value of `INVOICE_COMMENT_OVERRIDE` env variable when specified")
        .isEqualTo("custom_comment");

    // Verify Budget Line creation
    ArgumentCaptor<BudgetLine> budgetLineCaptor = ArgumentCaptor.forClass(BudgetLine.class);
    verify(patriciaDaoMock, times(1)).addBudgetLine(budgetLineCaptor.capture());
    List<BudgetLine> budgetLines = budgetLineCaptor.getAllValues();

    assertThat(budgetLines.get(0).caseId())
        .as("budget line should have correct case id")
        .isEqualTo(patriciaCase1.caseId());
    assertThat(budgetLines.get(0).workCodeId())
        .as("should use zero amount work code")
        .isEqualTo("zero1");
    assertThat(budgetLines.get(0).submissionDate())
        .as("submission date should equal to the current DB date")
        .isEqualTo(dbDate);
    assertThat(budgetLines.get(0).currency())
        .as("currency should be set")
        .isEqualTo(currency);
    assertThat(budgetLines.get(0).hourlyRate())
        .as("currency should be set")
        .isEqualTo(hourlyRate);
    assertThat(budgetLines.get(0).actualWorkTotalHours())
        .as("actual hours should corresponds to the total rows duration, disregarding user experience")
        .isEqualTo(BigDecimal.valueOf(0.25));
    assertThat(budgetLines.get(0).actualWorkTotalAmount())
        .as("chargeable hours should be 0 for zero charge work codes")
        .isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(budgetLines.get(0).chargeableAmount())
        .as("chargeable hours should be 0 for zero charge work codes")
        .isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(budgetLines.get(0).comment())
        .as("should use the value of `INVOICE_COMMENT_OVERRIDE` env variable when specified")
        .isEqualTo("custom_comment");
    assertThat(budgetLines.get(0).discountAmount())
        .as("discount amount should be zero when zero charge code is used")
        .isEqualTo(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
    assertThat(budgetLines.get(0).discountPercentage())
        .as("discount percentage should be zero when zero charge code is used")
        .isEqualTo(BigDecimal.ZERO);
    assertThat(budgetLines.get(0).chargeTypeId())
        .as("should use null when env variable `WT_CHARGE_TYPE_ID` is not specified")
        .isNull();
  }

  @Test
  @SuppressWarnings({"MethodLength", "ExecutableStatementCount"})
  void postTime_systemCurrency() {
    RuntimeConfig.setProperty(ConnectorLauncher.PatriciaConnectorConfigKey.USE_SYSDEFAULT_CURRENCY_FOR_POSTING, "true");
    final Tag tag1 = FAKE_ENTITIES.randomTag(TAG_UPSERT_PATH, "tag1");

    final TimeRow timeRow1 = FAKE_ENTITIES.randomTimeRow().activityHour(2018110121)
        .activityTypeCode("zero1")
        .durationSecs(600)
        .firstObservedInHour(0)
        .description(FAKER.lorem().characters());
    final TimeRow timeRow2 = FAKE_ENTITIES.randomTimeRow()
        .activityHour(2018110122)
        .activityTypeCode("zero1")
        .durationSecs(300)
        .firstObservedInHour(0)
        .description(FAKER.lorem().characters());

    final User user = FAKE_ENTITIES.randomUser().experienceWeightingPercent(50);

    final TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup()
        .tags(ImmutableList.of(tag1))
        .timeRows(ImmutableList.of(timeRow1, timeRow2))
        .user(user)
        .totalDurationSecs(900);

    RuntimeConfig.setProperty(ConnectorLauncher.PatriciaConnectorConfigKey.INVOICE_COMMENT_OVERRIDE, "custom_comment");

    final Case patriciaCase1 = randomDataGenerator.randomCase(tag1.getName());

    when(patriciaDaoMock.findCaseByCaseNumber(anyString()))
        .thenReturn(Optional.of(patriciaCase1));

    String userLogin = FAKER.internet().uuid();
    String dbDate = LocalDateTime.now().toString();
    String currency = FAKER.currency().code();
    BigDecimal hourlyRate = BigDecimal.TEN;

    when(patriciaDaoMock.findLoginIdByEmail(timeGroup.getUser().getExternalId())).thenReturn(Optional.of(userLogin));
    when(patriciaDaoMock.findPatPersonHourlyRate(any(), eq(userLogin))).thenReturn(Optional.of(hourlyRate));
    when(patriciaDaoMock.getDbDate()).thenReturn(dbDate);
    when(patriciaDaoMock.getSystemDefaultCurrency()).thenReturn(Optional.of(currency));

    assertThat(connector.postTime(fakeRequest(), timeGroup).getStatus())
        .as("Valid time group should be posted successfully")
        .isEqualTo(PostResultStatus.SUCCESS);

    // Verify Time Registration creation
    ArgumentCaptor<TimeRegistration> timeRegCaptor = ArgumentCaptor.forClass(TimeRegistration.class);
    verify(patriciaDaoMock, times(1)).addTimeRegistration(timeRegCaptor.capture());
    List<TimeRegistration> timeRegistrations = timeRegCaptor.getAllValues();

    assertThat(timeRegistrations.get(0).caseId())
        .as("time registration should have correct case id")
        .isEqualTo(patriciaCase1.caseId());
    assertThat(timeRegistrations.get(0).workCodeId())
        .as("should use zero amount work code")
        .isEqualTo("zero1");
    assertThat(timeRegistrations.get(0).submissionDate())
        .as("submission date should equal to the current DB date")
        .isEqualTo(dbDate);
    assertThat(timeRegistrations.get(0).activityDate())
        .as("activity date should equal to the activity date of the row in user time zone")
        .isEqualTo("2018-11-02");
    assertThat(timeRegistrations.get(0).actualHours())
        .as("actual hours should corresponds to the total rows duration, disregarding user experience")
        .isEqualTo(BigDecimal.valueOf(0.25));
    assertThat(timeRegistrations.get(0).chargeableHours())
        .as("chargeable hours should be 0 for zero charge work codes")
        .isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(timeRegistrations.get(0).comment())
        .as("should use the value of `INVOICE_COMMENT_OVERRIDE` env variable when specified")
        .isEqualTo("custom_comment");

    // Verify Budget Line creation
    ArgumentCaptor<BudgetLine> budgetLineCaptor = ArgumentCaptor.forClass(BudgetLine.class);
    verify(patriciaDaoMock, times(1)).addBudgetLine(budgetLineCaptor.capture());
    List<BudgetLine> budgetLines = budgetLineCaptor.getAllValues();

    assertThat(budgetLines.get(0).caseId())
        .as("budget line should have correct case id")
        .isEqualTo(patriciaCase1.caseId());
    assertThat(budgetLines.get(0).workCodeId())
        .as("should use zero amount work code")
        .isEqualTo("zero1");
    assertThat(budgetLines.get(0).submissionDate())
        .as("submission date should equal to the current DB date")
        .isEqualTo(dbDate);
    assertThat(budgetLines.get(0).currency())
        .as("currency should be set")
        .isEqualTo(currency);
    assertThat(budgetLines.get(0).hourlyRate())
        .as("currency should be set")
        .isEqualTo(hourlyRate);
    assertThat(budgetLines.get(0).actualWorkTotalHours())
        .as("actual hours should corresponds to the total rows duration, disregarding user experience")
        .isEqualTo(BigDecimal.valueOf(0.25));
    assertThat(budgetLines.get(0).actualWorkTotalAmount())
        .as("chargeable hours should be 0 for zero charge work codes")
        .isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(budgetLines.get(0).chargeableAmount())
        .as("chargeable hours should be 0 for zero charge work codes")
        .isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(budgetLines.get(0).comment())
        .as("should use the value of `INVOICE_COMMENT_OVERRIDE` env variable when specified")
        .isEqualTo("custom_comment");
    assertThat(budgetLines.get(0).discountAmount())
        .as("discount amount should be zero when zero charge code is used")
        .isEqualTo(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
    assertThat(budgetLines.get(0).discountPercentage())
        .as("discount percentage should be zero when zero charge code is used")
        .isEqualTo(BigDecimal.ZERO);
    assertThat(budgetLines.get(0).chargeTypeId())
        .as("should use null when env variable `WT_CHARGE_TYPE_ID` is not specified")
        .isNull();
    verify(patriciaDaoMock, never()).findCurrency(anyLong(), anyInt());
    RuntimeConfig.clearProperty(ConnectorLauncher.PatriciaConnectorConfigKey.USE_SYSDEFAULT_CURRENCY_FOR_POSTING);
  }

  @Test
  @SuppressWarnings({"MethodLength", "ExecutableStatementCount"})
  void postTime_systemCurrencyWithFallBack() {
    RuntimeConfig.setProperty(ConnectorLauncher.PatriciaConnectorConfigKey.USE_SYSDEFAULT_CURRENCY_FOR_POSTING, "true");
    String currency = FAKER.currency().code();
    RuntimeConfig.setProperty(PatriciaConnectorConfigKey.FALLBACK_CURRENCY, currency);
    final Tag tag1 = FAKE_ENTITIES.randomTag(TAG_UPSERT_PATH, "tag1");

    final TimeRow timeRow1 = FAKE_ENTITIES.randomTimeRow().activityHour(2018110121)
        .activityTypeCode("zero1")
        .durationSecs(600)
        .firstObservedInHour(0)
        .description(FAKER.lorem().characters());
    final TimeRow timeRow2 = FAKE_ENTITIES.randomTimeRow()
        .activityHour(2018110122)
        .activityTypeCode("zero1")
        .durationSecs(300)
        .firstObservedInHour(0)
        .description(FAKER.lorem().characters());

    final User user = FAKE_ENTITIES.randomUser().experienceWeightingPercent(50);

    final TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup()
        .tags(ImmutableList.of(tag1))
        .timeRows(ImmutableList.of(timeRow1, timeRow2))
        .user(user)
        .totalDurationSecs(900);

    RuntimeConfig.setProperty(ConnectorLauncher.PatriciaConnectorConfigKey.INVOICE_COMMENT_OVERRIDE, "custom_comment");

    final Case patriciaCase1 = randomDataGenerator.randomCase(tag1.getName());

    when(patriciaDaoMock.findCaseByCaseNumber(anyString()))
        .thenReturn(Optional.of(patriciaCase1));

    String userLogin = FAKER.internet().uuid();
    String dbDate = LocalDateTime.now().toString();
    BigDecimal hourlyRate = BigDecimal.TEN;

    when(patriciaDaoMock.findLoginIdByEmail(timeGroup.getUser().getExternalId())).thenReturn(Optional.of(userLogin));
    when(patriciaDaoMock.findPatPersonHourlyRate(any(), eq(userLogin))).thenReturn(Optional.of(hourlyRate));
    when(patriciaDaoMock.getDbDate()).thenReturn(dbDate);
    when(patriciaDaoMock.getSystemDefaultCurrency()).thenReturn(Optional.empty());

    assertThat(connector.postTime(fakeRequest(), timeGroup).getStatus())
        .as("Valid time group should be posted successfully")
        .isEqualTo(PostResultStatus.SUCCESS);

    // Verify Time Registration creation
    ArgumentCaptor<TimeRegistration> timeRegCaptor = ArgumentCaptor.forClass(TimeRegistration.class);
    verify(patriciaDaoMock, times(1)).addTimeRegistration(timeRegCaptor.capture());
    List<TimeRegistration> timeRegistrations = timeRegCaptor.getAllValues();

    assertThat(timeRegistrations.get(0).caseId())
        .as("time registration should have correct case id")
        .isEqualTo(patriciaCase1.caseId());
    assertThat(timeRegistrations.get(0).workCodeId())
        .as("should use zero amount work code")
        .isEqualTo("zero1");
    assertThat(timeRegistrations.get(0).submissionDate())
        .as("submission date should equal to the current DB date")
        .isEqualTo(dbDate);
    assertThat(timeRegistrations.get(0).activityDate())
        .as("activity date should equal to the activity date of the row in user time zone")
        .isEqualTo("2018-11-02");
    assertThat(timeRegistrations.get(0).actualHours())
        .as("actual hours should corresponds to the total rows duration, disregarding user experience")
        .isEqualTo(BigDecimal.valueOf(0.25));
    assertThat(timeRegistrations.get(0).chargeableHours())
        .as("chargeable hours should be 0 for zero charge work codes")
        .isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(timeRegistrations.get(0).comment())
        .as("should use the value of `INVOICE_COMMENT_OVERRIDE` env variable when specified")
        .isEqualTo("custom_comment");

    // Verify Budget Line creation
    ArgumentCaptor<BudgetLine> budgetLineCaptor = ArgumentCaptor.forClass(BudgetLine.class);
    verify(patriciaDaoMock, times(1)).addBudgetLine(budgetLineCaptor.capture());
    List<BudgetLine> budgetLines = budgetLineCaptor.getAllValues();

    assertThat(budgetLines.get(0).caseId())
        .as("budget line should have correct case id")
        .isEqualTo(patriciaCase1.caseId());
    assertThat(budgetLines.get(0).workCodeId())
        .as("should use zero amount work code")
        .isEqualTo("zero1");
    assertThat(budgetLines.get(0).submissionDate())
        .as("submission date should equal to the current DB date")
        .isEqualTo(dbDate);
    assertThat(budgetLines.get(0).currency())
        .as("currency should be set")
        .isEqualTo(currency);
    assertThat(budgetLines.get(0).hourlyRate())
        .as("currency should be set")
        .isEqualTo(hourlyRate);
    assertThat(budgetLines.get(0).actualWorkTotalHours())
        .as("actual hours should corresponds to the total rows duration, disregarding user experience")
        .isEqualTo(BigDecimal.valueOf(0.25));
    assertThat(budgetLines.get(0).actualWorkTotalAmount())
        .as("chargeable hours should be 0 for zero charge work codes")
        .isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(budgetLines.get(0).chargeableAmount())
        .as("chargeable hours should be 0 for zero charge work codes")
        .isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(budgetLines.get(0).comment())
        .as("should use the value of `INVOICE_COMMENT_OVERRIDE` env variable when specified")
        .isEqualTo("custom_comment");
    assertThat(budgetLines.get(0).discountAmount())
        .as("discount amount should be zero when zero charge code is used")
        .isEqualTo(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
    assertThat(budgetLines.get(0).discountPercentage())
        .as("discount percentage should be zero when zero charge code is used")
        .isEqualTo(BigDecimal.ZERO);
    assertThat(budgetLines.get(0).chargeTypeId())
        .as("should use null when env variable `WT_CHARGE_TYPE_ID` is not specified")
        .isNull();
    verify(patriciaDaoMock, never()).findCurrency(anyLong(), anyInt());
    RuntimeConfig.clearProperty(ConnectorLauncher.PatriciaConnectorConfigKey.USE_SYSDEFAULT_CURRENCY_FOR_POSTING);
    RuntimeConfig.clearProperty(PatriciaConnectorConfigKey.FALLBACK_CURRENCY);
  }

  @Test
  void convertToZone() {
    final TimeRow timeRow = FAKE_ENTITIES.randomTimeRow()
        .activityHour(2018123123)
        .firstObservedInHour(12)
        .submittedDate(20190110082359997L);
    final TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup()
        .timeRows(ImmutableList.of(timeRow));

    final TimeGroup convertedTimeGroup = connector.convertToZone(timeGroup, ZoneId.of("Asia/Kolkata")); // offset is +5.5

    // check TimeGroup
    assertThat(convertedTimeGroup)
        .as("original time group should not be mutated")
        .isNotSameAs(timeGroup);
    assertThat(convertedTimeGroup.getCallerKey()).isEqualTo(timeGroup.getCallerKey());
    assertThat(convertedTimeGroup.getGroupId()).isEqualTo(timeGroup.getGroupId());
    assertThat(convertedTimeGroup.getGroupName()).isEqualTo(timeGroup.getGroupName());
    assertThat(convertedTimeGroup.getDescription()).isEqualTo(timeGroup.getDescription());
    assertThat(convertedTimeGroup.getTotalDurationSecs()).isEqualTo(timeGroup.getTotalDurationSecs());
    assertThat(convertedTimeGroup.getNarrativeType()).isEqualTo(timeGroup.getNarrativeType());
    assertThat(convertedTimeGroup.getTags()).isEqualTo(timeGroup.getTags());
    assertThat(convertedTimeGroup.getUser()).isEqualTo(timeGroup.getUser());
    assertThat(convertedTimeGroup.getTags()).isEqualTo(timeGroup.getTags());

    // check TimeRow
    assertThat(convertedTimeGroup.getTimeRows().get(0))
        .as("original time group should not be mutated")
        .isNotSameAs(timeRow);
    assertThat(convertedTimeGroup.getTimeRows().get(0).getActivityHour())
        .as("should be converted to the specified timezone")
        .isEqualTo(2019010104);
    assertThat(convertedTimeGroup.getTimeRows().get(0).getFirstObservedInHour())
        .as("should be converted to the specified timezone")
        .isEqualTo(42);
    assertThat(convertedTimeGroup.getTimeRows().get(0).getSubmittedDate())
        .as("should be converted to the specified timezone")
        .isEqualTo(20190110135359997L);
    assertThat(convertedTimeGroup.getTimeRows().get(0).getActivity()).isEqualTo(timeRow.getActivity());
    assertThat(convertedTimeGroup.getTimeRows().get(0).getDescription()).isEqualTo(timeRow.getDescription());
    assertThat(convertedTimeGroup.getTimeRows().get(0).getDurationSecs()).isEqualTo(timeRow.getDurationSecs());
    assertThat(convertedTimeGroup.getTimeRows().get(0).getActivityTypeCode()).isEqualTo(timeRow.getActivityTypeCode());
  }

  @Test
  void postTime_should_use_external_id_as_username() {
    final String externalId = "i.am.login.id";
    final TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup(ACTIVITY_TYPE_CODE)
        .tags(ImmutableList.of(FAKE_ENTITIES.randomTag(TAG_UPSERT_PATH, "tag")))
        .user(FAKE_ENTITIES.randomUser().externalId(externalId));
    setPrerequisitesForSuccessfulPostTime(timeGroup);
    when(patriciaDaoMock.loginIdExists(externalId)).thenReturn(true);

    assertThat(connector.postTime(fakeRequest(), timeGroup).getStatus())
        .as("Valid time group should be posted successfully")
        .isEqualTo(PostResultStatus.SUCCESS);

    final ArgumentCaptor<TimeRegistration> timeRegCaptor = ArgumentCaptor.forClass(TimeRegistration.class);
    verify(patriciaDaoMock, times(timeGroup.getTags().size())).addTimeRegistration(timeRegCaptor.capture());
    assertThat(timeRegCaptor.getValue().userId())
        .as("should use the external id as login id")
        .isEqualTo(externalId);

    verify(patriciaDaoMock, never()).findLoginIdByEmail(any());
  }

  @Test
  void postTime_should_use_external_id_as_email() {
    final String externalId = "this-looks@like.email";
    final String loginId = "i.am.login.id";
    final TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup(ACTIVITY_TYPE_CODE)
        .tags(ImmutableList.of(FAKE_ENTITIES.randomTag(TAG_UPSERT_PATH, "tag")))
        .user(FAKE_ENTITIES.randomUser().externalId(externalId));
    setPrerequisitesForSuccessfulPostTime(timeGroup);

    when(patriciaDaoMock.loginIdExists(externalId)).thenReturn(false);
    when(patriciaDaoMock.findLoginIdByEmail(externalId)).thenReturn(Optional.of(loginId));

    assertThat(connector.postTime(fakeRequest(), timeGroup).getStatus())
        .as("Valid time group should be posted successfully")
        .isEqualTo(PostResultStatus.SUCCESS);

    final ArgumentCaptor<TimeRegistration> timeRegCaptor = ArgumentCaptor.forClass(TimeRegistration.class);
    verify(patriciaDaoMock, times(timeGroup.getTags().size())).addTimeRegistration(timeRegCaptor.capture());
    assertThat(timeRegCaptor.getValue().userId())
        .as("should look for Patricia user with email as the external id " +
            "if latter is not a Patricia login ID but looks like an email.")
        .isEqualTo(loginId);
  }

  @Test
  void postTime_should_use_email_for_getting_user() {
    final String patLoginId = "valid-patricia-login-id";
    final TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup(ACTIVITY_TYPE_CODE)
        .tags(ImmutableList.of(FAKE_ENTITIES.randomTag(TAG_UPSERT_PATH, "tag")))
        .user(FAKE_ENTITIES.randomUser().externalId(null)); // set external id to enable email check
    setPrerequisitesForSuccessfulPostTime(timeGroup);
    when(patriciaDaoMock.findLoginIdByEmail(timeGroup.getUser().getEmail())).thenReturn(Optional.of(patLoginId));

    assertThat(connector.postTime(fakeRequest(), timeGroup).getStatus())
        .as("Valid time group should be posted successfully")
        .isEqualTo(PostResultStatus.SUCCESS);

    final ArgumentCaptor<TimeRegistration> timeRegCaptor = ArgumentCaptor.forClass(TimeRegistration.class);
    verify(patriciaDaoMock, times(timeGroup.getTags().size())).addTimeRegistration(timeRegCaptor.capture());
    assertThat(timeRegCaptor.getValue().userId())
        .as("should user email to look for Patricia user if external id is not set.")
        .isEqualTo(patLoginId);

    verify(patriciaDaoMock, never()).loginIdExists(anyString());
  }

  private void setPrerequisitesForSuccessfulPostTime(TimeGroup timeGroup) {
    timeGroup.getTags().forEach(tag -> when(patriciaDaoMock.findCaseByCaseNumber(tag.getName()))
        .thenReturn(Optional.of(randomDataGenerator.randomCase(tag.getName()))));

    when(patriciaDaoMock.findPatPersonHourlyRate(any(), anyString()))
        .thenReturn(Optional.of(new BigDecimal(FAKER.number().numberBetween(10, 99))));
    when(patriciaDaoMock.getDbDate()).thenReturn(LocalDateTime.now().toString());
    when(patriciaDaoMock.findCurrency(anyLong(), anyInt())).thenReturn(Optional.of(FAKER.currency().code()));
  }

  private void verifyPatriciaNotUpdated() {
    verify(patriciaDaoMock, never()).updateBudgetHeader(anyLong(), anyString());
    verify(patriciaDaoMock, never()).addTimeRegistration(any());
    verify(patriciaDaoMock, never()).addBudgetLine(any());
  }

  private Request fakeRequest() {
    return mock(Request.class);
  }

}

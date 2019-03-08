/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.patricia;

import com.google.common.collect.ImmutableList;
import com.google.inject.Guice;

import com.github.javafaker.Faker;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import io.wisetime.connector.api_client.ApiClient;
import io.wisetime.connector.api_client.PostResult;
import io.wisetime.connector.config.ConnectorConfigKey;
import io.wisetime.connector.config.RuntimeConfig;
import io.wisetime.connector.datastore.ConnectorStore;
import io.wisetime.connector.integrate.ConnectorModule;
import io.wisetime.generated.connect.Tag;
import io.wisetime.generated.connect.TimeGroup;
import io.wisetime.generated.connect.TimeRow;
import io.wisetime.generated.connect.User;
import spark.Request;

import static io.wisetime.connector.patricia.PatriciaDao.BudgetLine;
import static io.wisetime.connector.patricia.PatriciaDao.Case;
import static io.wisetime.connector.patricia.PatriciaDao.TimeRegistration;
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

  private static final String DEFAULT_MODIFIER = "defaultModifier";
  private static final String ANOTHER_MODIFIER = "otherModifier";

  private static RandomDataGenerator randomDataGenerator = new RandomDataGenerator();
  private static PatriciaDao patriciaDaoMock = mock(PatriciaDao.class);
  private static ApiClient apiClientMock = mock(ApiClient.class);
  private static ConnectorStore connectorStoreMock = mock(ConnectorStore.class);
  private static PatriciaConnector connector;

  @BeforeAll
  static void setUp() {
    RuntimeConfig.rebuild();
    RuntimeConfig.setProperty(ConnectorLauncher.PatriciaConnectorConfigKey.DEFAULT_MODIFIER, DEFAULT_MODIFIER);
    RuntimeConfig.setProperty(
        ConnectorLauncher.PatriciaConnectorConfigKey.TAG_MODIFIER_WORK_CODE_MAPPING,
        String.format("%s:DM, %s:OM", DEFAULT_MODIFIER, ANOTHER_MODIFIER)
    );
    RuntimeConfig.setProperty(ConnectorLauncher.PatriciaConnectorConfigKey.TIMEZONE, "Asia/Manila");

    // Set a role type id to use
    RuntimeConfig.setProperty(ConnectorLauncher.PatriciaConnectorConfigKey.PATRICIA_ROLE_TYPE_ID, "4");

    connector = Guice.createInjector(
        binder -> binder.bind(PatriciaDao.class).toProvider(() -> patriciaDaoMock)
    )
        .getInstance(PatriciaConnector.class);

    // Ensure PatriciaConnector#init will not fail
    doReturn(true).when(patriciaDaoMock).hasExpectedSchema();

    connector.init(new ConnectorModule(apiClientMock, connectorStoreMock));
  }

  @BeforeEach
  void setUpTest() {
    RuntimeConfig.clearProperty(ConnectorConfigKey.CALLER_KEY);

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
  void postTime_wrongCallerId() {
    RuntimeConfig.setProperty(ConnectorConfigKey.CALLER_KEY, FAKER.lorem().word());
    assertThat(connector.postTime(fakeRequest(), FAKE_ENTITIES.randomTimeGroup()))
        .as("caller id not matched")
        .isEqualTo(PostResult.PERMANENT_FAILURE.withMessage("Invalid caller key in post time webhook call"));

    verifyZeroInteractions(patriciaDaoMock);
  }

  @Test
  void postTime_noTags() {
    TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup();
    timeGroup.setTags(Collections.emptyList());
    RuntimeConfig.setProperty(ConnectorConfigKey.CALLER_KEY, timeGroup.getCallerKey());

    assertThat(connector.postTime(fakeRequest(), timeGroup))
        .as("no tags in time group")
        .isEqualTo(PostResult.SUCCESS.withMessage("Time group has no tags. There is nothing to post to Patricia."));

    verifyZeroInteractions(patriciaDaoMock);
  }

  @Test
  void postTime_noTimeRows() {
    TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup();
    timeGroup.setTimeRows(Collections.emptyList());
    RuntimeConfig.setProperty(ConnectorConfigKey.CALLER_KEY, timeGroup.getCallerKey());

    assertThat(connector.postTime(fakeRequest(), timeGroup))
        .as("no time rows in time group")
        .isEqualTo(PostResult.PERMANENT_FAILURE.withMessage("Cannot post time group with no time rows"));

    verifyZeroInteractions(patriciaDaoMock);
  }

  @Test
  void postTime_cant_find_user_for_external_id_as_login_id() {
    final String externalId = "i.am.login.id";
    final TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup(DEFAULT_MODIFIER)
        .user(FAKE_ENTITIES.randomUser()
            .externalId(externalId));

    when(patriciaDaoMock.findUserByLoginId(externalId)).thenReturn(Optional.empty());

    PostResult result = connector.postTime(fakeRequest(), timeGroup);
    assertThat(result)
        .as("Can't post time because external id is not a valid Patricia login ID")
        .isEqualTo(PostResult.PERMANENT_FAILURE);
    assertThat(result.getMessage())
        .as("should be the correct error message for invalid user")
        .contains("User does not exist: " + timeGroup.getUser().getExternalId());

    verify(patriciaDaoMock, never()).findUserByEmail(anyString());
    verifyPatriciaNotUpdated();
  }

  @Test
  void postTime_cant_find_user_for_external_id_as_email() {
    final String externalId = "this-looks@like.email";
    final TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup(DEFAULT_MODIFIER)
        .user(FAKE_ENTITIES.randomUser()
            .externalId(externalId));

    when(patriciaDaoMock.findUserByLoginId(externalId)).thenReturn(Optional.empty());
    when(patriciaDaoMock.findUserByEmail(externalId)).thenReturn(Optional.empty());

    PostResult result = connector.postTime(fakeRequest(), timeGroup);
    assertThat(result)
        .as("Can't post time because external id is not a valid Patricia login ID or email")
        .isEqualTo(PostResult.PERMANENT_FAILURE);
    assertThat(result.getMessage())
        .as("should be the correct error message for invalid user")
        .contains("User does not exist: " + timeGroup.getUser().getExternalId());

    verifyPatriciaNotUpdated();
  }

  @Test
  void postTime_cant_find_user_for_email() {
    final TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup(DEFAULT_MODIFIER)
        .user(FAKE_ENTITIES.randomUser()
            .externalId(null)); // we should only check on email if external id is not set

    when(patriciaDaoMock.findUserByEmail(timeGroup.getUser().getEmail())).thenReturn(Optional.empty());

    PostResult result = connector.postTime(fakeRequest(), timeGroup);
    assertThat(result)
        .as("Can't post time because no user has this email in Patricia")
        .isEqualTo(PostResult.PERMANENT_FAILURE);
    assertThat(result.getMessage())
        .as("should be the correct error message for invalid user")
        .contains("User does not exist: " + timeGroup.getUser().getExternalId());

    verify(patriciaDaoMock, never()).findUserByLoginId(anyString());
    verifyPatriciaNotUpdated();
  }

  @Test
  void postTime_noHourlyRate() {
    TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup(DEFAULT_MODIFIER);
    RuntimeConfig.setProperty(ConnectorConfigKey.CALLER_KEY, timeGroup.getCallerKey());

    String userLogin = FAKER.internet().uuid();
    when(patriciaDaoMock.findUserByEmail(timeGroup.getUser().getExternalId())).thenReturn(Optional.of(userLogin));

    assertThat(connector.postTime(fakeRequest(), timeGroup))
        .as("failed to load database date")
        .isEqualTo(PostResult.PERMANENT_FAILURE.withMessage("No hourly rate is found for " + userLogin));

    verifyPatriciaNotUpdated();
  }


  @Test
  void postTime_unable_to_get_date_from_db() {
    final Tag tag = FAKE_ENTITIES.randomTag("/Patricia/");
    final TimeRow timeRow = FAKE_ENTITIES.randomTimeRow().modifier("").activityHour(2018110110);
    timeRow.setDescription(FAKER.lorem().characters());
    final User user = FAKE_ENTITIES.randomUser().experienceWeightingPercent(50);

    final TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup()
        .tags(ImmutableList.of(tag))
        .timeRows(ImmutableList.of(timeRow))
        .user(user)
        .durationSplitStrategy(TimeGroup.DurationSplitStrategyEnum.DIVIDE_BETWEEN_TAGS)
        .totalDurationSecs(1500);

    RuntimeConfig.setProperty(ConnectorConfigKey.CALLER_KEY, timeGroup.getCallerKey());

    String userLogin = FAKER.internet().uuid();
    when(patriciaDaoMock.findUserByEmail(timeGroup.getUser().getExternalId())).thenReturn(Optional.of(userLogin));
    when(patriciaDaoMock.findUserHourlyRate(any(), eq(userLogin))).thenReturn(Optional.of(BigDecimal.TEN));
    when(patriciaDaoMock.findCaseByCaseNumber(tag.getName())).thenReturn(Optional.of(randomDataGenerator.randomCase()));

    assertThat(connector.postTime(fakeRequest(), timeGroup))
        .as("failed to load database date")
        .isEqualTo(PostResult.TRANSIENT_FAILURE);

    verifyPatriciaNotUpdated();
  }

  @Test
  void postTime_unable_to_get_currency() {
    final Tag tag = FAKE_ENTITIES.randomTag("/Patricia/");
    final TimeRow timeRow = FAKE_ENTITIES.randomTimeRow().modifier("").activityHour(2018110110);
    timeRow.setDescription(FAKER.lorem().characters());
    final User user = FAKE_ENTITIES.randomUser().experienceWeightingPercent(50);

    final TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup()
        .tags(ImmutableList.of(tag))
        .timeRows(ImmutableList.of(timeRow))
        .user(user)
        .durationSplitStrategy(TimeGroup.DurationSplitStrategyEnum.WHOLE_DURATION_TO_EACH_TAG)
        .totalDurationSecs(1500);

    final Case patriciaCase = randomDataGenerator.randomCase();

    RuntimeConfig.setProperty(ConnectorConfigKey.CALLER_KEY, timeGroup.getCallerKey());

    String userLogin = FAKER.internet().uuid();
    when(patriciaDaoMock.findUserByEmail(timeGroup.getUser().getExternalId())).thenReturn(Optional.of(userLogin));
    when(patriciaDaoMock.findUserHourlyRate(any(), eq(userLogin))).thenReturn(Optional.of(BigDecimal.TEN));
    when(patriciaDaoMock.findCaseByCaseNumber(tag.getName())).thenReturn(Optional.of(patriciaCase));
    when(patriciaDaoMock.getDbDate()).thenReturn(LocalDateTime.now().toString());

    assertThat(connector.postTime(fakeRequest(), timeGroup))
        .as("unable to find currency for the case")
        .isEqualTo(PostResult.TRANSIENT_FAILURE);

    verifyPatriciaNotUpdated();
  }

  @Test
  void postTime_multiple_modifier() {
    final Tag tag = FAKE_ENTITIES.randomTag("/Patricia/");
    final TimeRow timeRow1 = FAKE_ENTITIES.randomTimeRow().modifier(DEFAULT_MODIFIER).activityHour(2018110110);
    final TimeRow timeRow2 = FAKE_ENTITIES.randomTimeRow().modifier(ANOTHER_MODIFIER).activityHour(2018110110);
    final User user = FAKE_ENTITIES.randomUser().experienceWeightingPercent(50);

    final TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup()
        .tags(ImmutableList.of(tag))
        .timeRows(ImmutableList.of(timeRow1, timeRow2))
        .user(user)
        .durationSplitStrategy(TimeGroup.DurationSplitStrategyEnum.DIVIDE_BETWEEN_TAGS)
        .totalDurationSecs(1500);

    RuntimeConfig.setProperty(ConnectorConfigKey.CALLER_KEY, timeGroup.getCallerKey());

    assertThat(connector.postTime(fakeRequest(), timeGroup))
        .as("Time group contains invalid modifier.")
        .isEqualTo(PostResult.PERMANENT_FAILURE);

    verifyPatriciaNotUpdated();
  }

  @Test
  void postTime() {
    final Tag tag1 = FAKE_ENTITIES.randomTag("/Patricia/");
    final Tag tag2 = FAKE_ENTITIES.randomTag("/Patricia/");
    final Tag tag3 = FAKE_ENTITIES.randomTag("/Patricia/");

    final TimeRow timeRow1 = FAKE_ENTITIES.randomTimeRow().activityHour(2018110121).modifier("").durationSecs(600);
    timeRow1.setDescription(FAKER.lorem().characters());
    final TimeRow timeRow2 = FAKE_ENTITIES.randomTimeRow().activityHour(2018110122).modifier("").durationSecs(300);
    timeRow2.setDescription(FAKER.lorem().characters());

    final User user = FAKE_ENTITIES.randomUser().experienceWeightingPercent(50);

    final TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup()
        .tags(ImmutableList.of(tag1, tag2, tag3))
        .timeRows(ImmutableList.of(timeRow1, timeRow2))
        .user(user)
        .durationSplitStrategy(TimeGroup.DurationSplitStrategyEnum.DIVIDE_BETWEEN_TAGS)
        .totalDurationSecs(1500);

    RuntimeConfig.setProperty(ConnectorConfigKey.CALLER_KEY, timeGroup.getCallerKey());
    RuntimeConfig.setProperty(ConnectorLauncher.PatriciaConnectorConfigKey.INVOICE_COMMENT_OVERRIDE, "custom_comment");

    final Case patriciaCase1 = randomDataGenerator.randomCase(tag1.getName());
    final Case patriciaCase2 = randomDataGenerator.randomCase(tag2.getName());

    when(patriciaDaoMock.findCaseByCaseNumber(anyString()))
        .thenReturn(Optional.of(patriciaCase1))
        .thenReturn(Optional.of(patriciaCase2))
        .thenReturn(Optional.empty()); // Last tag has no matching Patricia issue

    String userLogin = FAKER.internet().uuid();
    String dbDate = LocalDateTime.now().toString();
    String currency = FAKER.currency().code();
    BigDecimal hourlyRate = BigDecimal.TEN;

    when(patriciaDaoMock.findUserByEmail(timeGroup.getUser().getExternalId())).thenReturn(Optional.of(userLogin));
    when(patriciaDaoMock.findUserHourlyRate(any(), eq(userLogin))).thenReturn(Optional.of(hourlyRate));
    when(patriciaDaoMock.getDbDate()).thenReturn(dbDate);
    when(patriciaDaoMock.findCurrency(anyLong(), anyInt())).thenReturn(Optional.of(currency));

    assertThat(connector.postTime(fakeRequest(), timeGroup))
        .as("Valid time group should be posted successfully")
        .isEqualTo(PostResult.SUCCESS);

    // Verify Time Registration creation
    ArgumentCaptor<TimeRegistration> timeRegCaptor = ArgumentCaptor.forClass(TimeRegistration.class);
    verify(patriciaDaoMock, times(2)).addTimeRegistration(timeRegCaptor.capture());
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
        .isEqualTo("2018-11-02 05:00:00");
    assertThat(timeRegistrations.get(0).actualHours())
        .as("actual hours should corresponds to the total rows duration, disregarding user experience and " +
            "split equally between all tags ")
        .isEqualTo(BigDecimal.valueOf(0.08));
    assertThat(timeRegistrations.get(0).chargeableHours())
        .as("chargeable hours should corresponds to the group duration, excluding user experience and " +
            "split equally between all tags ")
        .isEqualByComparingTo(BigDecimal.valueOf(.14));
    assertThat(timeRegistrations.get(0).comment())
        .as("should use the value of `INVOICE_COMMENT_OVERRIDE` env variable when specified")
        .isEqualTo("custom_comment");

    // Verify Budget Line creation
    ArgumentCaptor<BudgetLine> budgetLineCaptor = ArgumentCaptor.forClass(BudgetLine.class);
    verify(patriciaDaoMock, times(2)).addBudgetLine(budgetLineCaptor.capture());
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
        .as("hourly rate * actual hours (without experience rating)")
        .isEqualByComparingTo(BigDecimal.valueOf(1.40));
    assertThat(budgetLines.get(0).chargeableAmount())
        .as("hourly rate * chargeable hours (applying experience rating)")
        .isEqualByComparingTo(BigDecimal.valueOf(.70));
    assertThat(budgetLines.get(0).comment())
        .as("should use the value of `INVOICE_COMMENT_OVERRIDE` env variable when specified")
        .isEqualTo("custom_comment");
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
    assertThat(convertedTimeGroup.getDurationSplitStrategy()).isEqualTo(timeGroup.getDurationSplitStrategy());
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
    assertThat(convertedTimeGroup.getTimeRows().get(0).getModifier()).isEqualTo(timeRow.getModifier());
  }

  @Test
  void postTime_should_use_external_id_as_username() {
    final String externalId = "i.am.login.id";
    final TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup(DEFAULT_MODIFIER)
        .user(FAKE_ENTITIES.randomUser().externalId(externalId));
    setPrerequisitesForSuccessfulPostTime(timeGroup);
    when(patriciaDaoMock.findUserByLoginId(externalId)).thenReturn(Optional.of(externalId));

    assertThat(connector.postTime(fakeRequest(), timeGroup))
        .as("Valid time group should be posted successfully")
        .isEqualTo(PostResult.SUCCESS);

    final ArgumentCaptor<TimeRegistration> timeRegCaptor = ArgumentCaptor.forClass(TimeRegistration.class);
    verify(patriciaDaoMock, times(timeGroup.getTags().size())).addTimeRegistration(timeRegCaptor.capture());
    assertThat(timeRegCaptor.getValue().userId())
        .as("should use the external id as login id")
        .isEqualTo(externalId);

    verify(patriciaDaoMock, never()).findUserByEmail(any());
  }

  @Test
  void postTime_should_use_external_id_as_email() {
    final String externalId = "this-looks@like.email";
    final String loginId = "i.am.login.id";
    final TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup(DEFAULT_MODIFIER)
        .user(FAKE_ENTITIES.randomUser().externalId(externalId));
    setPrerequisitesForSuccessfulPostTime(timeGroup);

    when(patriciaDaoMock.findUserByLoginId(externalId)).thenReturn(Optional.empty());
    when(patriciaDaoMock.findUserByEmail(externalId)).thenReturn(Optional.of(loginId));

    assertThat(connector.postTime(fakeRequest(), timeGroup))
        .as("Valid time group should be posted successfully")
        .isEqualTo(PostResult.SUCCESS);

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
    final TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup(DEFAULT_MODIFIER)
        .user(FAKE_ENTITIES.randomUser().externalId(null)); // set external id to enable email check
    setPrerequisitesForSuccessfulPostTime(timeGroup);
    when(patriciaDaoMock.findUserByEmail(timeGroup.getUser().getEmail())).thenReturn(Optional.of(patLoginId));

    assertThat(connector.postTime(fakeRequest(), timeGroup))
        .as("Valid time group should be posted successfully")
        .isEqualTo(PostResult.SUCCESS);

    final ArgumentCaptor<TimeRegistration> timeRegCaptor = ArgumentCaptor.forClass(TimeRegistration.class);
    verify(patriciaDaoMock, times(timeGroup.getTags().size())).addTimeRegistration(timeRegCaptor.capture());
    assertThat(timeRegCaptor.getValue().userId())
        .as("should user email to look for Patricia user if external id is not set.")
        .isEqualTo(patLoginId);

    verify(patriciaDaoMock, never()).findUserByLoginId(anyString());
  }

  private void setPrerequisitesForSuccessfulPostTime(TimeGroup timeGroup) {
    RuntimeConfig.setProperty(ConnectorConfigKey.CALLER_KEY, timeGroup.getCallerKey());

    timeGroup.getTags().forEach(tag -> when(patriciaDaoMock.findCaseByCaseNumber(tag.getName()))
        .thenReturn(Optional.of(randomDataGenerator.randomCase(tag.getName()))));

    when(patriciaDaoMock.findUserHourlyRate(any(), anyString()))
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
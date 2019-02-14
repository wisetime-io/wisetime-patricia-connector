/*
 * Copyright (c) 2019 Practice Insight Pty Ltd. All rights reserved.
 */

package io.wisetime.connector.patricia;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.inject.Guice;

import com.github.javafaker.Faker;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author alvin.llobrera@practiceinsight.io
 */
public class PatriciaConnectorPostTimeNarrativeTest {

  private static final Faker FAKER = new Faker();
  private static final FakeEntities FAKE_ENTITIES = new FakeEntities();

  private static RandomDataGenerator randomDataGenerator = new RandomDataGenerator();
  private static PatriciaDao patriciaDaoMock = mock(PatriciaDao.class);
  private static ApiClient apiClientMock = mock(ApiClient.class);
  private static ConnectorStore connectorStoreMock = mock(ConnectorStore.class);
  private static PatriciaConnector connectorNoRowDuration;
  private static PatriciaConnector connectorWithRowDuration;

  @BeforeAll
  static void setUp() {
    RuntimeConfig.rebuild();
    RuntimeConfig.setProperty(ConnectorLauncher.PatriciaConnectorConfigKey.DEFAULT_MODIFIER, "defaultModifier");
    RuntimeConfig.setProperty(
        ConnectorLauncher.PatriciaConnectorConfigKey.TAG_MODIFIER_WORK_CODE_MAPPING, "defaultModifier:DM, modifier2:M2"
    );
    RuntimeConfig.setProperty(ConnectorLauncher.PatriciaConnectorConfigKey.TIMEZONE, "Asia/Manila");
    RuntimeConfig.setProperty(ConnectorLauncher.PatriciaConnectorConfigKey.PATRICIA_ROLE_TYPE_ID, "4");

    // Ensure PatriciaConnector#init will not fail
    doReturn(true).when(patriciaDaoMock).hasExpectedSchema();

    // create connector to test for narrative not showing row duration
    connectorNoRowDuration = Guice.createInjector(
        binder -> binder.bind(PatriciaDao.class).toProvider(() -> patriciaDaoMock)
    )
        .getInstance(PatriciaConnector.class);
    connectorNoRowDuration.init(new ConnectorModule(apiClientMock, connectorStoreMock));

    // create connector to test for narrative showing row duration
    RuntimeConfig.setProperty(ConnectorLauncher.PatriciaConnectorConfigKey.INCLUDE_DURATIONS_IN_INVOICE_COMMENT, "true");
    connectorWithRowDuration = Guice.createInjector(
        binder -> binder.bind(PatriciaDao.class).toProvider(() -> patriciaDaoMock)
    )
        .getInstance(PatriciaConnector.class);
    connectorWithRowDuration.init(new ConnectorModule(apiClientMock, connectorStoreMock));
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
  void postTime_no_duration() {
    final Tag tag = FAKE_ENTITIES.randomTag("/Patricia/");
    final TimeRow timeRow = FAKE_ENTITIES.randomTimeRow()
        .modifier("")
        .activityHour(2018110110)
        .firstObservedInHour(2)
        .durationSecs(1000)
        .description(FAKER.superhero().descriptor());
    final User user = FAKE_ENTITIES.randomUser().experienceWeightingPercent(50);
    RuntimeConfig.setProperty(ConnectorLauncher.PatriciaConnectorConfigKey.INVOICE_COMMENT_OVERRIDE, null);

    final TimeGroup timeGroup = expectSuccessfulPostingTime(
        user, ImmutableList.of(timeRow), Lists.newArrayList(tag)
    )
        .totalDurationSecs(1500)
        .durationSplitStrategy(TimeGroup.DurationSplitStrategyEnum.DIVIDE_BETWEEN_TAGS)
        .narrativeType(TimeGroup.NarrativeTypeEnum.AND_TIME_ROW_ACTIVITY_DESCRIPTIONS);

    assertThat(connectorNoRowDuration.postTime(mock(Request.class), timeGroup))
        .as("Valid time group should be posted successfully")
        .isEqualTo(PostResult.SUCCESS);

    // Verify Time Registration creation
    ArgumentCaptor<PatriciaDao.TimeRegistration> timeRegCaptor = ArgumentCaptor.forClass(PatriciaDao.TimeRegistration.class);
    verify(patriciaDaoMock).addTimeRegistration(timeRegCaptor.capture());
    assertThat(timeRegCaptor.getValue().comment())
        .as("should use template if `INVOICE_COMMENT_OVERRIDE` env variable is not set")
        .startsWith(timeGroup.getDescription());
    assertThat(timeRegCaptor.getValue().comment())
        .as("should include time row details with start time converted in configured time zone")
        .contains("18:02 - " + timeRow.getActivity() + " - " + timeRow.getDescription());

    // Verify Budget Line creation
    ArgumentCaptor<PatriciaDao.BudgetLine> budgetLineCaptor = ArgumentCaptor.forClass(PatriciaDao.BudgetLine.class);
    verify(patriciaDaoMock).addBudgetLine(budgetLineCaptor.capture());
    assertThat(budgetLineCaptor.getValue().comment())
        .as("should use template if `INVOICE_COMMENT_OVERRIDE` env variable is not set")
        .startsWith(timeGroup.getDescription());
    assertThat(budgetLineCaptor.getValue().comment())
        .as("should include time row details with start time converted in configured time zone")
        .contains("18:02 - " + timeRow.getActivity() + " - " + timeRow.getDescription());
    assertThat(budgetLineCaptor.getValue().comment())
        .as("should use template if `INVOICE_COMMENT_OVERRIDE` env variable is not set")
        .contains("Total worked time: 16m 40s\n" +
            "Total chargeable time: 25m\n" +
            "Experience factor: 50%");
  }

  @Test
  void postTime_no_duration_divide_between_tags() {
    final Tag tag1 = FAKE_ENTITIES.randomTag("/Patricia/");
    final Tag tag2 = FAKE_ENTITIES.randomTag("/Patricia/");
    final TimeRow timeRow = FAKE_ENTITIES.randomTimeRow()
        .modifier("")
        .activityHour(2019060409)
        .firstObservedInHour(3)
        .durationSecs(120)
        .description(FAKER.superhero().descriptor());
    final User user = FAKE_ENTITIES.randomUser().experienceWeightingPercent(100);
    RuntimeConfig.setProperty(ConnectorLauncher.PatriciaConnectorConfigKey.INVOICE_COMMENT_OVERRIDE, null);

    final TimeGroup timeGroup = expectSuccessfulPostingTime(
        user, ImmutableList.of(timeRow), Lists.newArrayList(tag1, tag2)
    )
        .totalDurationSecs(300)
        .durationSplitStrategy(TimeGroup.DurationSplitStrategyEnum.DIVIDE_BETWEEN_TAGS)
        .narrativeType(TimeGroup.NarrativeTypeEnum.AND_TIME_ROW_ACTIVITY_DESCRIPTIONS);

    assertThat(connectorNoRowDuration.postTime(mock(Request.class), timeGroup))
        .as("Valid time group should be posted successfully")
        .isEqualTo(PostResult.SUCCESS);

    ArgumentCaptor<PatriciaDao.BudgetLine> budgetLineCaptor = ArgumentCaptor.forClass(PatriciaDao.BudgetLine.class);
    verify(patriciaDaoMock, times(2)).addBudgetLine(budgetLineCaptor.capture());
    final String budgetLineComment = budgetLineCaptor.getAllValues().get(0).comment();
    assertThat(budgetLineComment)
        .as("should use template if `INVOICE_COMMENT_OVERRIDE` env variable is not set")
        .startsWith(timeGroup.getDescription());
    assertThat(budgetLineComment)
        .as("should include time row details with start time converted in configured time zone")
        .contains("17:03 - " + timeRow.getActivity() + " - " + timeRow.getDescription());
    assertThat(budgetLineComment)
        .as("total worked and chargeable time should appear if split strategy is 'divide between tags'")
        .contains("Total worked time: 2m\n" +
            "Total chargeable time: 5m\n" +
            "Experience factor: 100%");
    assertThat(budgetLineComment)
        .as("disclaimer about duration split should appear if split strategy is 'divide between tags'")
        .endsWith("\nThe above times have been split across 2 cases and are " +
            "thus greater than the chargeable time in this case");
  }

  @Test
  void postTime_no_duration_whole_duration_for_each_tag() {
    final Tag tag1 = FAKE_ENTITIES.randomTag("/Patricia/");
    final Tag tag2 = FAKE_ENTITIES.randomTag("/Patricia/");
    final TimeRow timeRow = FAKE_ENTITIES.randomTimeRow()
        .modifier("")
        .activityHour(2019060409)
        .firstObservedInHour(4)
        .durationSecs(120)
        .description(FAKER.superhero().descriptor());
    final User user = FAKE_ENTITIES.randomUser().experienceWeightingPercent(100);
    RuntimeConfig.setProperty(ConnectorLauncher.PatriciaConnectorConfigKey.INVOICE_COMMENT_OVERRIDE, null);

    final TimeGroup timeGroup = expectSuccessfulPostingTime(
        user, ImmutableList.of(timeRow), Lists.newArrayList(tag1, tag2)
    )
        .totalDurationSecs(300)
        .durationSplitStrategy(TimeGroup.DurationSplitStrategyEnum.WHOLE_DURATION_TO_EACH_TAG)
        .narrativeType(TimeGroup.NarrativeTypeEnum.AND_TIME_ROW_ACTIVITY_DESCRIPTIONS);

    assertThat(connectorNoRowDuration.postTime(mock(Request.class), timeGroup))
        .as("Valid time group should be posted successfully")
        .isEqualTo(PostResult.SUCCESS);

    ArgumentCaptor<PatriciaDao.BudgetLine> budgetLineCaptor = ArgumentCaptor.forClass(PatriciaDao.BudgetLine.class);
    verify(patriciaDaoMock, times(2)).addBudgetLine(budgetLineCaptor.capture());
    final String budgetLineComment = budgetLineCaptor.getAllValues().get(0).comment();
    assertThat(budgetLineComment)
        .as("should use template if `INVOICE_COMMENT_OVERRIDE` env variable is not set")
        .startsWith(timeGroup.getDescription());
    assertThat(budgetLineComment)
        .as("should include time row details with start time converted in configured time zone")
        .contains("17:04 - " + timeRow.getActivity() + " - " + timeRow.getDescription());
    assertThat(budgetLineComment)
        .endsWith("Total worked time: 2m\n" +
            "Total chargeable time: 5m\n" +
            "Experience factor: 100%");
  }

  @Test
  void postTime_no_row_duration_narrative_only() {
    final Tag tag1 = FAKE_ENTITIES.randomTag("/Patricia/");
    final Tag tag2 = FAKE_ENTITIES.randomTag("/Patricia/");
    final TimeRow timeRow1 = FAKE_ENTITIES.randomTimeRow().modifier("").activityHour(2016050113).durationSecs(240);
    final TimeRow timeRow2 = FAKE_ENTITIES.randomTimeRow().modifier("").activityHour(2016050113).durationSecs(120);
    final User user = FAKE_ENTITIES.randomUser().experienceWeightingPercent(100);
    RuntimeConfig.setProperty(ConnectorLauncher.PatriciaConnectorConfigKey.INVOICE_COMMENT_OVERRIDE, null);

    final TimeGroup timeGroup = expectSuccessfulPostingTime(
        user, ImmutableList.of(timeRow1, timeRow2), Lists.newArrayList(tag1, tag2)
    )
        .totalDurationSecs(300)
        .durationSplitStrategy(TimeGroup.DurationSplitStrategyEnum.WHOLE_DURATION_TO_EACH_TAG)
        .narrativeType(TimeGroup.NarrativeTypeEnum.ONLY);

    assertThat(connectorNoRowDuration.postTime(mock(Request.class), timeGroup))
        .as("Valid time group should be posted successfully")
        .isEqualTo(PostResult.SUCCESS);

    ArgumentCaptor<PatriciaDao.BudgetLine> budgetLineCaptor = ArgumentCaptor.forClass(PatriciaDao.BudgetLine.class);
    verify(patriciaDaoMock, times(2)).addBudgetLine(budgetLineCaptor.capture());
    final String budgetLineComment = budgetLineCaptor.getAllValues().get(0).comment();
    assertThat(budgetLineComment)
        .as("should display narrative only")
        .startsWith(timeGroup.getDescription())
        .doesNotContain(timeRow1.getActivity() + " - " + timeRow1.getDescription())
        .doesNotContain(timeRow2.getActivity() + " - " + timeRow2.getDescription())
        .endsWith("Total worked time: 6m\n" +
            "Total chargeable time: 5m\n" +
            "Experience factor: 100%");
  }

  @Test
  void postTime_with_row_duration() {
    final Tag tag = FAKE_ENTITIES.randomTag("/Patricia/");
    final TimeRow timeRow = FAKE_ENTITIES.randomTimeRow()
        .modifier("")
        .activityHour(2019031815)
        .firstObservedInHour(5)
        .durationSecs(1000)
        .description(FAKER.superhero().descriptor());
    final User user = FAKE_ENTITIES.randomUser().experienceWeightingPercent(50);
    RuntimeConfig.setProperty(ConnectorLauncher.PatriciaConnectorConfigKey.INVOICE_COMMENT_OVERRIDE, null);

    final TimeGroup timeGroup = expectSuccessfulPostingTime(
        user, ImmutableList.of(timeRow), Lists.newArrayList(tag)
    )
        .totalDurationSecs(1500)
        .durationSplitStrategy(TimeGroup.DurationSplitStrategyEnum.DIVIDE_BETWEEN_TAGS)
        .narrativeType(TimeGroup.NarrativeTypeEnum.AND_TIME_ROW_ACTIVITY_DESCRIPTIONS);

    assertThat(connectorWithRowDuration.postTime(mock(Request.class), timeGroup))
        .as("Valid time group should be posted successfully")
        .isEqualTo(PostResult.SUCCESS);

    // Verify Time Registration creation
    ArgumentCaptor<PatriciaDao.TimeRegistration> timeRegCaptor = ArgumentCaptor.forClass(PatriciaDao.TimeRegistration.class);
    verify(patriciaDaoMock).addTimeRegistration(timeRegCaptor.capture());
    assertThat(timeRegCaptor.getValue().comment())
        .as("should use template if `INVOICE_COMMENT_OVERRIDE` env variable is not set")
        .startsWith(timeGroup.getDescription());
    assertThat(timeRegCaptor.getValue().comment())
        .as("should include time row details with start time converted in configured time zone")
        .contains("23:05 hrs - [16m 40s] - " + timeRow.getActivity() + " - " + timeRow.getDescription());

    // Verify Budget Line creation
    ArgumentCaptor<PatriciaDao.BudgetLine> budgetLineCaptor = ArgumentCaptor.forClass(PatriciaDao.BudgetLine.class);
    verify(patriciaDaoMock).addBudgetLine(budgetLineCaptor.capture());
    assertThat(budgetLineCaptor.getValue().comment())
        .as("should use template if `INVOICE_COMMENT_OVERRIDE` env variable is not set")
        .startsWith(timeGroup.getDescription());
    assertThat(budgetLineCaptor.getValue().comment())
        .as("should include time row details with start time converted in configured time zone")
        .contains("23:05 hrs - [16m 40s] - " + timeRow.getActivity() + " - " + timeRow.getDescription());
    assertThat(budgetLineCaptor.getValue().comment())
        .as("should use template if `INVOICE_COMMENT_OVERRIDE` env variable is not set")
        .contains("Total worked time: 16m 40s\n" +
            "Total chargeable time: 25m\n" +
            "Experience factor: 50%");
  }

  @Test
  void postTime_with_row_duration_divide_between_tags() {
    final Tag tag1 = FAKE_ENTITIES.randomTag("/Patricia/");
    final Tag tag2 = FAKE_ENTITIES.randomTag("/Patricia/");
    final TimeRow timeRow = FAKE_ENTITIES.randomTimeRow()
        .modifier("")
        .activityHour(2019060409)
        .firstObservedInHour(6)
        .durationSecs(120)
        .description(FAKER.superhero().descriptor());
    final User user = FAKE_ENTITIES.randomUser().experienceWeightingPercent(100);
    RuntimeConfig.setProperty(ConnectorLauncher.PatriciaConnectorConfigKey.INVOICE_COMMENT_OVERRIDE, null);

    final TimeGroup timeGroup = expectSuccessfulPostingTime(
        user, ImmutableList.of(timeRow), Lists.newArrayList(tag1, tag2)
    )
        .totalDurationSecs(300)
        .durationSplitStrategy(TimeGroup.DurationSplitStrategyEnum.DIVIDE_BETWEEN_TAGS)
        .narrativeType(TimeGroup.NarrativeTypeEnum.AND_TIME_ROW_ACTIVITY_DESCRIPTIONS);

    assertThat(connectorWithRowDuration.postTime(mock(Request.class), timeGroup))
        .as("Valid time group should be posted successfully")
        .isEqualTo(PostResult.SUCCESS);

    ArgumentCaptor<PatriciaDao.BudgetLine> budgetLineCaptor = ArgumentCaptor.forClass(PatriciaDao.BudgetLine.class);
    verify(patriciaDaoMock, times(2)).addBudgetLine(budgetLineCaptor.capture());
    final String budgetLineComment = budgetLineCaptor.getAllValues().get(0).comment();
    assertThat(budgetLineComment)
        .as("should use template if `INVOICE_COMMENT_OVERRIDE` env variable is not set")
        .startsWith(timeGroup.getDescription());
    assertThat(budgetLineComment)
        .as("should include time row details with start time converted in configured time zone")
        .contains("17:06 hrs - [2m] - " + timeRow.getActivity() + " - " + timeRow.getDescription());
    assertThat(budgetLineComment)
        .as("total worked and chargeable time should appear if split strategy is 'divide between tags'")
        .contains("Total worked time: 2m\n" +
            "Total chargeable time: 5m\n" +
            "Experience factor: 100%");
    assertThat(budgetLineComment)
        .as("disclaimer about duration split should appear if split strategy is 'divide between tags'")
        .endsWith("\nThe above times have been split across 2 cases and are " +
            "thus greater than the chargeable time in this case");
  }

  @Test
  void postTime_with_row_duration_whole_duration_for_each_tag() {
    final Tag tag1 = FAKE_ENTITIES.randomTag("/Patricia/");
    final Tag tag2 = FAKE_ENTITIES.randomTag("/Patricia/");
    final TimeRow timeRow = FAKE_ENTITIES.randomTimeRow()
        .modifier("")
        .activityHour(2016050113)
        .firstObservedInHour(7)
        .durationSecs(120)
        .description(FAKER.superhero().descriptor());
    final User user = FAKE_ENTITIES.randomUser().experienceWeightingPercent(100);
    RuntimeConfig.setProperty(ConnectorLauncher.PatriciaConnectorConfigKey.INVOICE_COMMENT_OVERRIDE, null);

    final TimeGroup timeGroup = expectSuccessfulPostingTime(
        user, ImmutableList.of(timeRow), Lists.newArrayList(tag1, tag2)
    )
        .totalDurationSecs(300)
        .durationSplitStrategy(TimeGroup.DurationSplitStrategyEnum.WHOLE_DURATION_TO_EACH_TAG)
        .narrativeType(TimeGroup.NarrativeTypeEnum.AND_TIME_ROW_ACTIVITY_DESCRIPTIONS);

    assertThat(connectorWithRowDuration.postTime(mock(Request.class), timeGroup))
        .as("Valid time group should be posted successfully")
        .isEqualTo(PostResult.SUCCESS);

    ArgumentCaptor<PatriciaDao.BudgetLine> budgetLineCaptor = ArgumentCaptor.forClass(PatriciaDao.BudgetLine.class);
    verify(patriciaDaoMock, times(2)).addBudgetLine(budgetLineCaptor.capture());
    final String budgetLineComment = budgetLineCaptor.getAllValues().get(0).comment();
    assertThat(budgetLineComment)
        .as("should use template if `INVOICE_COMMENT_OVERRIDE` env variable is not set")
        .startsWith(timeGroup.getDescription());
    assertThat(budgetLineComment)
        .as("should include time row details with start time converted in configured time zone")
        .contains("21:07 hrs - [2m] - " + timeRow.getActivity() + " - " + timeRow.getDescription());
    assertThat(budgetLineComment)
        .endsWith("Total worked time: 2m\n" +
            "Total chargeable time: 5m\n" +
            "Experience factor: 100%");
  }

  @Test
  void postTime_with_row_duration_narrative_only() {
    final Tag tag1 = FAKE_ENTITIES.randomTag("/Patricia/");
    final Tag tag2 = FAKE_ENTITIES.randomTag("/Patricia/");
    final TimeRow timeRow1 = FAKE_ENTITIES.randomTimeRow().modifier("").activityHour(2017050113).durationSecs(360);
    final TimeRow timeRow2 = FAKE_ENTITIES.randomTimeRow().modifier("").activityHour(2017050113).durationSecs(360);
    final User user = FAKE_ENTITIES.randomUser().experienceWeightingPercent(100);
    RuntimeConfig.setProperty(ConnectorLauncher.PatriciaConnectorConfigKey.INVOICE_COMMENT_OVERRIDE, null);

    final TimeGroup timeGroup = expectSuccessfulPostingTime(
        user, ImmutableList.of(timeRow1, timeRow2), Lists.newArrayList(tag1, tag2)
    )
        .totalDurationSecs(300)
        .durationSplitStrategy(TimeGroup.DurationSplitStrategyEnum.WHOLE_DURATION_TO_EACH_TAG)
        .narrativeType(TimeGroup.NarrativeTypeEnum.ONLY);

    assertThat(connectorWithRowDuration.postTime(mock(Request.class), timeGroup))
        .as("Valid time group should be posted successfully")
        .isEqualTo(PostResult.SUCCESS);

    ArgumentCaptor<PatriciaDao.BudgetLine> budgetLineCaptor = ArgumentCaptor.forClass(PatriciaDao.BudgetLine.class);
    verify(patriciaDaoMock, times(2)).addBudgetLine(budgetLineCaptor.capture());
    final String budgetLineComment = budgetLineCaptor.getAllValues().get(0).comment();
    assertThat(budgetLineComment)
        .as("should display narrative only")
        .startsWith(timeGroup.getDescription())
        .doesNotContain(timeRow1.getActivity() + " - " + timeRow1.getDescription())
        .doesNotContain(timeRow2.getActivity() + " - " + timeRow2.getDescription())
        .endsWith("Total worked time: 12m\n" +
            "Total chargeable time: 5m\n" +
            "Experience factor: 100%");
  }

  private TimeGroup expectSuccessfulPostingTime(User user,
                                                List<TimeRow> timeRows,
                                                List<Tag> tags) {
    final TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup()
        .tags(tags)
        .timeRows(timeRows)
        .user(user);

    RuntimeConfig.setProperty(ConnectorConfigKey.CALLER_KEY, timeGroup.getCallerKey());

    tags.forEach(tag -> when(patriciaDaoMock.findCaseByCaseNumber(tag.getName()))
        .thenReturn(Optional.of(randomDataGenerator.randomCase(tag.getName()))));

    String userLogin = FAKER.internet().uuid();
    String dbDate = LocalDateTime.now().toString();
    String currency = FAKER.currency().code();
    BigDecimal hourlyRate = BigDecimal.TEN;

    when(patriciaDaoMock.findLoginByEmail(timeGroup.getUser().getExternalId())).thenReturn(Optional.of(userLogin));
    when(patriciaDaoMock.findUserHourlyRate(any(), eq(userLogin))).thenReturn(Optional.of(hourlyRate));
    when(patriciaDaoMock.getDbDate()).thenReturn(dbDate);
    when(patriciaDaoMock.findCurrency(anyLong(), anyInt())).thenReturn(Optional.of(currency));
    return timeGroup;
  }
}

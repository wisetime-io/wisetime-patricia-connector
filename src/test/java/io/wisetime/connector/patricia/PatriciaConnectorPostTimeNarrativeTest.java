/*
 * Copyright (c) 2019 Practice Insight Pty Ltd. All rights reserved.
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
import java.util.Optional;

import io.wisetime.connector.ConnectorModule;
import io.wisetime.connector.api_client.ApiClient;
import io.wisetime.connector.api_client.PostResult.PostResultStatus;
import io.wisetime.connector.config.ConnectorConfigKey;
import io.wisetime.connector.config.RuntimeConfig;
import io.wisetime.connector.datastore.ConnectorStore;
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
class PatriciaConnectorPostTimeNarrativeTest {

  private static final Faker FAKER = new Faker();
  private static final FakeEntities FAKE_ENTITIES = new FakeEntities();

  private static RandomDataGenerator randomDataGenerator = new RandomDataGenerator();
  private static PatriciaDao patriciaDaoMock = mock(PatriciaDao.class);
  private static ApiClient apiClientMock = mock(ApiClient.class);
  private static ConnectorStore connectorStoreMock = mock(ConnectorStore.class);
  private static PatriciaConnector connector;

  @BeforeAll
  static void setUp() {
    RuntimeConfig.rebuild();
    RuntimeConfig.setProperty(ConnectorLauncher.PatriciaConnectorConfigKey.TIMEZONE, "Asia/Manila");
    RuntimeConfig.setProperty(ConnectorLauncher.PatriciaConnectorConfigKey.PATRICIA_ROLE_TYPE_ID, "4");

    // create connector to test for narrative showing row duration
    connector = Guice.createInjector(
        binder -> binder.bind(PatriciaDao.class).toProvider(() -> patriciaDaoMock)
    )
        .getInstance(PatriciaConnector.class);
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

    // Ensure PatriciaConnector#init will not fail
    doReturn(true).when(patriciaDaoMock).hasExpectedSchema();
    RuntimeConfig.setProperty(ConnectorLauncher.PatriciaConnectorConfigKey.ADD_SUMMARY_TO_NARRATIVE, "false");
    connector.init(new ConnectorModule(apiClientMock, connectorStoreMock));
  }

  private void initConnectorWithSummaryTemplate() {
    doReturn(true).when(patriciaDaoMock).hasExpectedSchema();

    RuntimeConfig.setProperty(ConnectorLauncher.PatriciaConnectorConfigKey.ADD_SUMMARY_TO_NARRATIVE, "true");
    connector.init(new ConnectorModule(apiClientMock, mock(ConnectorStore.class)));
  }

  @AfterEach
  void cleanup() {
    RuntimeConfig.clearProperty(ConnectorLauncher.PatriciaConnectorConfigKey.INVOICE_COMMENT_OVERRIDE);
  }

  @Test
  void divide_between_tags() {
    initConnectorWithSummaryTemplate();
    final TimeRow earliestTimeRow = FAKE_ENTITIES.randomTimeRow()
        .activityTypeCode("DM").activityHour(2019031808).firstObservedInHour(5).durationSecs(3006);
    final TimeRow latestTimeRow = FAKE_ENTITIES.randomTimeRow()
        .activityTypeCode("DM").activityHour(2019031810).firstObservedInHour(8).durationSecs(1000);
    final User user = FAKE_ENTITIES.randomUser().experienceWeightingPercent(50);
    RuntimeConfig.setProperty(ConnectorLauncher.PatriciaConnectorConfigKey.INVOICE_COMMENT_OVERRIDE, null);

    final TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup()
        .tags(ImmutableList.of(FAKE_ENTITIES.randomTag("/Patricia/"), FAKE_ENTITIES.randomTag("/Patricia/")))
        .timeRows(ImmutableList.of(earliestTimeRow, latestTimeRow))
        .user(user)
        .totalDurationSecs(4006)
        .durationSplitStrategy(TimeGroup.DurationSplitStrategyEnum.DIVIDE_BETWEEN_TAGS)
        .narrativeType(TimeGroup.NarrativeTypeEnum.AND_TIME_ROW_ACTIVITY_DESCRIPTIONS);
    setPrerequisitesForSuccessfulPostTime(timeGroup);

    assertThat(connector.postTime(mock(Request.class), timeGroup).getStatus())
        .as("Valid time group should be posted successfully")
        .isEqualTo(PostResultStatus.SUCCESS);

    // Verify Time Registration creation
    ArgumentCaptor<PatriciaDao.TimeRegistration> timeRegCaptor = ArgumentCaptor.forClass(PatriciaDao.TimeRegistration.class);
    verify(patriciaDaoMock, times(2)).addTimeRegistration(timeRegCaptor.capture());
    assertThat(timeRegCaptor.getAllValues().get(0).comment())
        .as("should use template if `INVOICE_COMMENT_OVERRIDE` env variable is not set")
        .startsWith(timeGroup.getDescription());
    assertThat(timeRegCaptor.getValue().comment())
        .as("should include time row details with start time converted in configured time zone")
        .contains(
            "\r\n16:00 - 16:59\n" +
            "- 50m 6s - " + earliestTimeRow.getActivity() + " - " + earliestTimeRow.getDescription() + "\n" +
            "\r\n18:00 - 18:59\n" +
            "- 16m 40s - " + latestTimeRow.getActivity() + " - " + latestTimeRow.getDescription())
        .contains("\r\nTotal Worked Time: 1h 6m 46s\n" +
            "Total Chargeable Time: 16m 42s")
        .contains("The chargeable time has been weighed based on an experience factor of 50%.")
        .endsWith("\r\nThe above times have been split across 2 cases and are thus greater than " +
            "the chargeable time in this case");
    assertThat(timeRegCaptor.getAllValues().get(0).comment())
        .as("narrative for all tags should be the same for time registration.")
        .isEqualTo(timeRegCaptor.getAllValues().get(1).comment());

    // Verify Budget Line creation
    ArgumentCaptor<PatriciaDao.BudgetLine> budgetLineCaptor = ArgumentCaptor.forClass(PatriciaDao.BudgetLine.class);
    verify(patriciaDaoMock, times(2)).addBudgetLine(budgetLineCaptor.capture());
    assertThat(budgetLineCaptor.getAllValues().get(0).comment())
        .as("should use template if `INVOICE_COMMENT_OVERRIDE` env variable is not set")
        .startsWith(timeGroup.getDescription());
    assertThat(budgetLineCaptor.getValue().comment())
        .as("should include time row details with start time converted in configured time zone")
        .contains(
            "\r\n16:00 - 16:59\n" +
            "- 50m 6s - " + earliestTimeRow.getActivity() + " - " + earliestTimeRow.getDescription() + "\n" +
            "\r\n18:00 - 18:59\n" +
            "- 16m 40s - " + latestTimeRow.getActivity() + " - " + latestTimeRow.getDescription());
    assertThat(budgetLineCaptor.getAllValues().get(0).comment())
        .as("narrative for all tags should be the same for budget line.")
        .isEqualTo(budgetLineCaptor.getAllValues().get(1).comment());
  }

  @Test
  void divide_between_tags_edited() {
    initConnectorWithSummaryTemplate();
    final TimeRow earliestTimeRow = FAKE_ENTITIES.randomTimeRow()
        .activityTypeCode("DM").activityHour(2019031808).firstObservedInHour(5).durationSecs(3006);
    final TimeRow latestTimeRow = FAKE_ENTITIES.randomTimeRow()
        .activityTypeCode("DM").activityHour(2019031810).firstObservedInHour(8).durationSecs(1000);
    final User user = FAKE_ENTITIES.randomUser().experienceWeightingPercent(50);
    RuntimeConfig.setProperty(ConnectorLauncher.PatriciaConnectorConfigKey.INVOICE_COMMENT_OVERRIDE, null);

    final TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup()
        .tags(ImmutableList.of(FAKE_ENTITIES.randomTag("/Patricia/"), FAKE_ENTITIES.randomTag("/Patricia/")))
        .timeRows(ImmutableList.of(earliestTimeRow, latestTimeRow))
        .user(user)
        .totalDurationSecs(3600)
        .durationSplitStrategy(TimeGroup.DurationSplitStrategyEnum.DIVIDE_BETWEEN_TAGS)
        .narrativeType(TimeGroup.NarrativeTypeEnum.AND_TIME_ROW_ACTIVITY_DESCRIPTIONS);
    setPrerequisitesForSuccessfulPostTime(timeGroup);

    assertThat(connector.postTime(mock(Request.class), timeGroup).getStatus())
        .as("Valid time group should be posted successfully")
        .isEqualTo(PostResultStatus.SUCCESS);

    // Verify Time Registration creation
    ArgumentCaptor<PatriciaDao.TimeRegistration> timeRegCaptor = ArgumentCaptor.forClass(PatriciaDao.TimeRegistration.class);
    verify(patriciaDaoMock, times(2)).addTimeRegistration(timeRegCaptor.capture());
    assertThat(timeRegCaptor.getAllValues().get(0).comment())
        .as("should use template if `INVOICE_COMMENT_OVERRIDE` env variable is not set")
        .startsWith(timeGroup.getDescription());
    assertThat(timeRegCaptor.getValue().comment())
        .as("should include time row details with start time converted in configured time zone")
        .contains(
            "\r\n16:00 - 16:59\n" +
                "- 50m 6s - " + earliestTimeRow.getActivity() + " - " + earliestTimeRow.getDescription() + "\n" +
                "\r\n18:00 - 18:59\n" +
                "- 16m 40s - " + latestTimeRow.getActivity() + " - " + latestTimeRow.getDescription())
        .contains("\r\nTotal Worked Time: 1h 6m 46s\n" +
            "Total Chargeable Time: 30m")
        .doesNotContain("The chargeable time has been weighed based on an experience factor")
        .endsWith("\r\nThe above times have been split across 2 cases and are thus greater than " +
            "the chargeable time in this case");
    assertThat(timeRegCaptor.getAllValues().get(0).comment())
        .as("narrative for all tags should be the same for time registration.")
        .isEqualTo(timeRegCaptor.getAllValues().get(1).comment());

    // Verify Budget Line creation
    ArgumentCaptor<PatriciaDao.BudgetLine> budgetLineCaptor = ArgumentCaptor.forClass(PatriciaDao.BudgetLine.class);
    verify(patriciaDaoMock, times(2)).addBudgetLine(budgetLineCaptor.capture());
    assertThat(budgetLineCaptor.getAllValues().get(0).comment())
        .as("should use template if `INVOICE_COMMENT_OVERRIDE` env variable is not set")
        .startsWith(timeGroup.getDescription());
    assertThat(budgetLineCaptor.getValue().comment())
        .as("should include time row details with start time converted in configured time zone")
        .contains(
            "\r\n16:00 - 16:59\n" +
                "- 50m 6s - " + earliestTimeRow.getActivity() + " - " + earliestTimeRow.getDescription() + "\n" +
                "\r\n18:00 - 18:59\n" +
                "- 16m 40s - " + latestTimeRow.getActivity() + " - " + latestTimeRow.getDescription());
    assertThat(budgetLineCaptor.getAllValues().get(0).comment())
        .as("narrative for all tags should be the same for budget line.")
        .isEqualTo(budgetLineCaptor.getAllValues().get(1).comment());
  }

  @Test
  void whole_duration_for_each_tag() {
    initConnectorWithSummaryTemplate();
    final TimeRow timeRow = FAKE_ENTITIES.randomTimeRow()
        .activityTypeCode("DM")
        .activityHour(2016050113)
        .firstObservedInHour(7)
        .durationSecs(120)
        .description(FAKER.superhero().descriptor());
    final User user = FAKE_ENTITIES.randomUser().experienceWeightingPercent(100);
    RuntimeConfig.setProperty(ConnectorLauncher.PatriciaConnectorConfigKey.INVOICE_COMMENT_OVERRIDE, null);

    final TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup()
        .tags(ImmutableList.of(FAKE_ENTITIES.randomTag("/Patricia/"), FAKE_ENTITIES.randomTag("/Patricia/")))
        .timeRows(ImmutableList.of(timeRow))
        .user(user)
        .totalDurationSecs(120)
        .durationSplitStrategy(TimeGroup.DurationSplitStrategyEnum.WHOLE_DURATION_TO_EACH_TAG)
        .narrativeType(TimeGroup.NarrativeTypeEnum.AND_TIME_ROW_ACTIVITY_DESCRIPTIONS);
    setPrerequisitesForSuccessfulPostTime(timeGroup);

    assertThat(connector.postTime(mock(Request.class), timeGroup).getStatus())
        .as("Valid time group should be posted successfully")
        .isEqualTo(PostResultStatus.SUCCESS);

    // Verify Time Registration creation
    ArgumentCaptor<PatriciaDao.TimeRegistration> timeRegCaptor = ArgumentCaptor.forClass(PatriciaDao.TimeRegistration.class);
    verify(patriciaDaoMock, times(2)).addTimeRegistration(timeRegCaptor.capture());
    assertThat(timeRegCaptor.getAllValues().get(0).comment())
        .as("should use template if `INVOICE_COMMENT_OVERRIDE` env variable is not set")
        .startsWith(timeGroup.getDescription());
    assertThat(timeRegCaptor.getValue().comment())
        .as("should include time row details with start time converted in configured time zone")
        .contains(
            "\r\n21:00 - 21:59\n" +
            "- 2m - " + timeRow.getActivity() + " - " + timeRow.getDescription())
        .doesNotContain("weighed based on an experience factor")
        .endsWith("\r\nTotal Worked Time: 2m\n" +
            "Total Chargeable Time: 2m");
    assertThat(timeRegCaptor.getAllValues().get(0).comment())
        .as("narrative for all tags should be the same for time registration.")
        .isEqualTo(timeRegCaptor.getAllValues().get(1).comment());

    // Verify Budget Line creation
    ArgumentCaptor<PatriciaDao.BudgetLine> budgetLineCaptor = ArgumentCaptor.forClass(PatriciaDao.BudgetLine.class);
    verify(patriciaDaoMock, times(2)).addBudgetLine(budgetLineCaptor.capture());
    final String budgetLineComment = budgetLineCaptor.getAllValues().get(0).comment();
    assertThat(budgetLineComment)
        .as("should use template if `INVOICE_COMMENT_OVERRIDE` env variable is not set")
        .startsWith(timeGroup.getDescription());
    assertThat(budgetLineComment)
        .as("should include time row details with start time converted in configured time zone")
        .contains(
            "\r\n21:00 - 21:59\n" +
            "- 2m - " + timeRow.getActivity() + " - " + timeRow.getDescription());
    assertThat(budgetLineCaptor.getAllValues().get(0).comment())
        .as("narrative for all tags should be the same for budget line.")
        .isEqualTo(budgetLineCaptor.getAllValues().get(1).comment());
  }

  @Test
  void whole_duration_for_each_tag_edited() {
    initConnectorWithSummaryTemplate();
    final TimeRow timeRow = FAKE_ENTITIES.randomTimeRow()
        .modifier("")
        .activityHour(2016050113)
        .firstObservedInHour(7)
        .durationSecs(120)
        .description(FAKER.superhero().descriptor());
    final User user = FAKE_ENTITIES.randomUser().experienceWeightingPercent(50);
    RuntimeConfig.setProperty(ConnectorLauncher.PatriciaConnectorConfigKey.INVOICE_COMMENT_OVERRIDE, null);

    final TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup()
        .tags(ImmutableList.of(FAKE_ENTITIES.randomTag("/Patricia/"), FAKE_ENTITIES.randomTag("/Patricia/")))
        .timeRows(ImmutableList.of(timeRow))
        .user(user)
        .totalDurationSecs(300)
        .durationSplitStrategy(TimeGroup.DurationSplitStrategyEnum.WHOLE_DURATION_TO_EACH_TAG)
        .narrativeType(TimeGroup.NarrativeTypeEnum.AND_TIME_ROW_ACTIVITY_DESCRIPTIONS);
    setPrerequisitesForSuccessfulPostTime(timeGroup);

    assertThat(connector.postTime(mock(Request.class), timeGroup).getStatus())
        .as("Valid time group should be posted successfully")
        .isEqualTo(PostResultStatus.SUCCESS);

    // Verify Time Registration creation
    ArgumentCaptor<PatriciaDao.TimeRegistration> timeRegCaptor = ArgumentCaptor.forClass(PatriciaDao.TimeRegistration.class);
    verify(patriciaDaoMock, times(2)).addTimeRegistration(timeRegCaptor.capture());
    assertThat(timeRegCaptor.getAllValues().get(0).comment())
        .as("should use template if `INVOICE_COMMENT_OVERRIDE` env variable is not set")
        .startsWith(timeGroup.getDescription());
    assertThat(timeRegCaptor.getValue().comment())
        .as("should include time row details with start time converted in configured time zone")
        .contains(
            "\r\n21:00 - 21:59\n" +
                "- 2m - " + timeRow.getActivity() + " - " + timeRow.getDescription())
        .doesNotContain("weighed based on an experience factor")
        .endsWith("\r\nTotal Worked Time: 2m\n" +
            "Total Chargeable Time: 5m");
    assertThat(timeRegCaptor.getAllValues().get(0).comment())
        .as("narrative for all tags should be the same for time registration.")
        .isEqualTo(timeRegCaptor.getAllValues().get(1).comment());

    // Verify Budget Line creation
    ArgumentCaptor<PatriciaDao.BudgetLine> budgetLineCaptor = ArgumentCaptor.forClass(PatriciaDao.BudgetLine.class);
    verify(patriciaDaoMock, times(2)).addBudgetLine(budgetLineCaptor.capture());
    final String budgetLineComment = budgetLineCaptor.getAllValues().get(0).comment();
    assertThat(budgetLineComment)
        .as("should use template if `INVOICE_COMMENT_OVERRIDE` env variable is not set")
        .startsWith(timeGroup.getDescription());
    assertThat(budgetLineComment)
        .as("should include time row details with start time converted in configured time zone")
        .contains(
            "\r\n21:00 - 21:59\n" +
                "- 2m - " + timeRow.getActivity() + " - " + timeRow.getDescription());
    assertThat(budgetLineCaptor.getAllValues().get(0).comment())
        .as("narrative for all tags should be the same for budget line.")
        .isEqualTo(budgetLineCaptor.getAllValues().get(1).comment());
  }

  @Test
  void narrative_only_no_summary() {
    final TimeRow timeRow1 = FAKE_ENTITIES.randomTimeRow().activityTypeCode("DM").activityHour(2017050113).durationSecs(360);
    final TimeRow timeRow2 = FAKE_ENTITIES.randomTimeRow().activityTypeCode("DM").activityHour(2017050113).durationSecs(360);
    final User user = FAKE_ENTITIES.randomUser().experienceWeightingPercent(100);
    RuntimeConfig.setProperty(ConnectorLauncher.PatriciaConnectorConfigKey.INVOICE_COMMENT_OVERRIDE, null);

    final TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup()
        .tags(ImmutableList.of(FAKE_ENTITIES.randomTag("/Patricia/"), FAKE_ENTITIES.randomTag("/Patricia/")))
        .timeRows(ImmutableList.of(timeRow1, timeRow2))
        .user(user)
        .totalDurationSecs(300)
        .durationSplitStrategy(TimeGroup.DurationSplitStrategyEnum.WHOLE_DURATION_TO_EACH_TAG)
        .narrativeType(TimeGroup.NarrativeTypeEnum.ONLY);
    setPrerequisitesForSuccessfulPostTime(timeGroup);

    assertThat(connector.postTime(mock(Request.class), timeGroup).getStatus())
        .as("Valid time group should be posted successfully")
        .isEqualTo(PostResultStatus.SUCCESS);

    ArgumentCaptor<PatriciaDao.TimeRegistration> timeRegistrationCaptor =
        ArgumentCaptor.forClass(PatriciaDao.TimeRegistration.class);
    verify(patriciaDaoMock, times(2)).addTimeRegistration(timeRegistrationCaptor.capture());
    final String budgetLineCommentForCase1 = timeRegistrationCaptor.getAllValues().get(0).comment();
    assertThat(budgetLineCommentForCase1)
        .as("should display narrative only")
        .startsWith(timeGroup.getDescription())
        .doesNotContain(timeRow1.getActivity() + " - " + timeRow1.getDescription())
        .doesNotContain(timeRow2.getActivity() + " - " + timeRow2.getDescription())
        // No summary block if NARRATIVE_ONLY and summary is disabled
        .doesNotContain("Total Worked Time:")
        .doesNotContain("Total Chargeable Time: 5m");
    assertThat(budgetLineCommentForCase1)
        .as("comment for the other case should be the same")
        .isEqualTo(timeRegistrationCaptor.getAllValues().get(1).comment());
  }

  @Test
  void narrative_only() {
    initConnectorWithSummaryTemplate();
    final TimeRow timeRow1 = FAKE_ENTITIES.randomTimeRow().activityTypeCode("DM").activityHour(2017050113).durationSecs(360);
    final TimeRow timeRow2 = FAKE_ENTITIES.randomTimeRow().activityTypeCode("DM").activityHour(2017050113).durationSecs(360);
    final User user = FAKE_ENTITIES.randomUser().experienceWeightingPercent(100);
    RuntimeConfig.setProperty(ConnectorLauncher.PatriciaConnectorConfigKey.INVOICE_COMMENT_OVERRIDE, null);

    final TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup()
        .tags(ImmutableList.of(FAKE_ENTITIES.randomTag("/Patricia/"), FAKE_ENTITIES.randomTag("/Patricia/")))
        .timeRows(ImmutableList.of(timeRow1, timeRow2))
        .user(user)
        .totalDurationSecs(300)
        .durationSplitStrategy(TimeGroup.DurationSplitStrategyEnum.WHOLE_DURATION_TO_EACH_TAG)
        .narrativeType(TimeGroup.NarrativeTypeEnum.ONLY);
    setPrerequisitesForSuccessfulPostTime(timeGroup);

    assertThat(connector.postTime(mock(Request.class), timeGroup).getStatus())
        .as("Valid time group should be posted successfully")
        .isEqualTo(PostResultStatus.SUCCESS);

    ArgumentCaptor<PatriciaDao.TimeRegistration> timeRegistrationCaptor =
        ArgumentCaptor.forClass(PatriciaDao.TimeRegistration.class);
    verify(patriciaDaoMock, times(2)).addTimeRegistration(timeRegistrationCaptor.capture());
    final String budgetLineCommentForCase1 = timeRegistrationCaptor.getAllValues().get(0).comment();
    assertThat(budgetLineCommentForCase1)
        .as("should display narrative only")
        .startsWith(timeGroup.getDescription())
        .doesNotContain(timeRow1.getActivity() + " - " + timeRow1.getDescription())
        .doesNotContain(timeRow2.getActivity() + " - " + timeRow2.getDescription())
        .endsWith("Total Worked Time: 12m\n"
            + "Total Chargeable Time: 5m");
    assertThat(budgetLineCommentForCase1)
        .as("comment for the other case should be the same")
        .isEqualTo(timeRegistrationCaptor.getAllValues().get(1).comment());
  }

  @Test
  void sanitize_app_name_and_window_title() {
    initConnectorWithSummaryTemplate();
    final TimeRow nullWindowTitle = FAKE_ENTITIES.randomTimeRow()
        .activity("@_Thinking_@").description(null).activityTypeCode("DM").activityHour(2018110109).durationSecs(120);
    final TimeRow emptyWindowTitle = FAKE_ENTITIES.randomTimeRow()
        .activity("@_Videocall_@")
        .description("@_empty_@")
        .activityTypeCode("DM")
        .activityHour(2018110109)
        .durationSecs(181);
    final User user = FAKE_ENTITIES.randomUser().experienceWeightingPercent(100);
    RuntimeConfig.setProperty(ConnectorLauncher.PatriciaConnectorConfigKey.INVOICE_COMMENT_OVERRIDE, null);

    final TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup()
        .tags(ImmutableList.of(FAKE_ENTITIES.randomTag("/Patricia/"), FAKE_ENTITIES.randomTag("/Patricia/")))
        .timeRows(ImmutableList.of(nullWindowTitle, emptyWindowTitle))
        .user(user)
        .totalDurationSecs(300)
        .durationSplitStrategy(TimeGroup.DurationSplitStrategyEnum.WHOLE_DURATION_TO_EACH_TAG)
        .narrativeType(TimeGroup.NarrativeTypeEnum.AND_TIME_ROW_ACTIVITY_DESCRIPTIONS);
    setPrerequisitesForSuccessfulPostTime(timeGroup);

    assertThat(connector.postTime(mock(Request.class), timeGroup).getStatus())
        .as("Valid time group should be posted successfully")
        .isEqualTo(PostResultStatus.SUCCESS);

    // Verify Time Registration creation
    ArgumentCaptor<PatriciaDao.TimeRegistration> timeRegCaptor = ArgumentCaptor.forClass(PatriciaDao.TimeRegistration.class);
    verify(patriciaDaoMock, times(2)).addTimeRegistration(timeRegCaptor.capture());
    assertThat(timeRegCaptor.getAllValues().get(0).comment())
        .as("should use template if `INVOICE_COMMENT_OVERRIDE` env variable is not set")
        .startsWith(timeGroup.getDescription());
    assertThat(timeRegCaptor.getValue().comment())
        .as("should sanitize manual time and blank window title")
        .contains(
            "\n\r\n17:00 - 17:59" +
            "\n- 2m - Thinking - No window title available" +
            "\n- 3m 1s - Videocall - No window title available")
        .endsWith("\nTotal Worked Time: 5m 1s\n" +
            "Total Chargeable Time: 5m");
    assertThat(timeRegCaptor.getAllValues().get(0).comment())
        .as("narrative for all tags should be the same for time registration.")
        .isEqualTo(timeRegCaptor.getAllValues().get(1).comment());

    // Verify Budget Line creation
    ArgumentCaptor<PatriciaDao.BudgetLine> budgetLineCaptor = ArgumentCaptor.forClass(PatriciaDao.BudgetLine.class);
    verify(patriciaDaoMock, times(2)).addBudgetLine(budgetLineCaptor.capture());
    final String budgetLineComment = budgetLineCaptor.getAllValues().get(0).comment();
    assertThat(budgetLineComment)
        .as("should use template if `INVOICE_COMMENT_OVERRIDE` env variable is not set")
        .startsWith(timeGroup.getDescription());
    assertThat(budgetLineComment)
        .as("should sanitize manual time and blank window title")
        .contains(
            "\n\r\n17:00 - 17:59" +
            "\n- 2m - Thinking - No window title available" +
            "\n- 3m 1s - Videocall - No window title available");
    assertThat(budgetLineCaptor.getAllValues().get(0).comment())
        .as("narrative for all tags should be the same for budget line.")
        .isEqualTo(budgetLineCaptor.getAllValues().get(1).comment());
  }

  private void setPrerequisitesForSuccessfulPostTime(TimeGroup timeGroup) {
    RuntimeConfig.setProperty(ConnectorConfigKey.CALLER_KEY, timeGroup.getCallerKey());

    timeGroup.getTags().forEach(tag -> when(patriciaDaoMock.findCaseByCaseNumber(tag.getName()))
        .thenReturn(Optional.of(randomDataGenerator.randomCase(tag.getName()))));

    String userLogin = FAKER.internet().uuid();
    String dbDate = LocalDateTime.now().toString();
    String currency = FAKER.currency().code();
    BigDecimal hourlyRate = BigDecimal.TEN;

    when(patriciaDaoMock.findLoginIdByEmail(timeGroup.getUser().getExternalId())).thenReturn(Optional.of(userLogin));
    when(patriciaDaoMock.findUserHourlyRate(any(), eq(userLogin))).thenReturn(Optional.of(hourlyRate));
    when(patriciaDaoMock.getDbDate()).thenReturn(dbDate);
    when(patriciaDaoMock.findCurrency(anyLong(), anyInt())).thenReturn(Optional.of(currency));
  }
}

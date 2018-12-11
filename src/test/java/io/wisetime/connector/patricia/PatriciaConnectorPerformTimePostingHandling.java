/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.patricia;

import com.google.common.collect.ImmutableMap;
import com.google.inject.Guice;

import com.github.javafaker.Faker;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Optional;

import io.wisetime.connector.api_client.ApiClient;
import io.wisetime.connector.api_client.PostResult;
import io.wisetime.connector.config.ConnectorConfigKey;
import io.wisetime.connector.config.RuntimeConfig;
import io.wisetime.connector.datastore.ConnectorStore;
import io.wisetime.connector.integrate.ConnectorModule;
import io.wisetime.connector.template.TemplateFormatter;
import io.wisetime.generated.connect.Tag;
import io.wisetime.generated.connect.TimeGroup;
import io.wisetime.generated.connect.TimeRow;

import static io.wisetime.connector.patricia.PatriciaDao.PostTimeData;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
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
  private static RandomDataGenerator randomDataGenerator = new RandomDataGenerator();
  private static PatriciaDao patriciaDao = mock(PatriciaDao.class);
  private static ApiClient apiClient = mock(ApiClient.class);
  private static ConnectorStore connectorStore = mock(ConnectorStore.class);
  private static PatriciaConnector connector;
  private static TemplateFormatter templateFormatter = mock(TemplateFormatter.class);

  @BeforeAll
  static void setUp() {
    RuntimeConfig.rebuild();
    RuntimeConfig.setProperty(ConnectorLauncher.PatriciaConnectorConfigKey.DEFAULT_MODIFIER, "test");
    RuntimeConfig.setProperty(ConnectorLauncher.PatriciaConnectorConfigKey.TAG_MODIFIER_WORK_CODE_MAPPING, "test:1");

    connector = Guice.createInjector(binder -> {
      binder.bind(PatriciaDao.class).toProvider(() -> patriciaDao);
    }).getInstance(PatriciaConnector.class);

    // Ensure PatriciaConnector#init will not fail
    doReturn(true).when(patriciaDao).isHealthy();

    connector.init(new ConnectorModule(apiClient, templateFormatter, connectorStore));
  }

  @BeforeEach
  void setUpTest() {
    reset(patriciaDao);
    reset(apiClient);
    reset(connectorStore);
    doAnswer(invocation -> {
      ((Runnable) invocation.getArgument(0)).run();
      return null;
    }).when(patriciaDao).asTransaction(any());
  }

  @AfterEach
  void cleanup() {
    RuntimeConfig.clearProperty(ConnectorLauncher.PatriciaConnectorConfigKey.INVOICE_COMMENT_OVERRIDE);
  }

  @Test
  void postTime_wrongCallerId() {
    RuntimeConfig.setProperty(ConnectorConfigKey.CALLER_KEY, FAKER.lorem().word());
    assertThat(connector.postTime(null, randomDataGenerator.randomTimeGroup()))
        .as("caller id not matched")
        .isEqualTo(PostResult.PERMANENT_FAILURE.withMessage("Invalid caller key in post time webhook call"));

    verifyZeroInteractions(patriciaDao);
  }

  @Test
  void postTime_noTags() {
    TimeGroup timeGroup = randomDataGenerator.randomTimeGroup();
    timeGroup.setTags(Collections.emptyList());
    RuntimeConfig.setProperty(ConnectorConfigKey.CALLER_KEY, timeGroup.getCallerKey());

    assertThat(connector.postTime(null, timeGroup))
        .as("no tags in time group")
        .isEqualTo(PostResult.SUCCESS.withMessage("Time group has no tags. There is nothing to post to Patricia."));

    verifyZeroInteractions(patriciaDao);
  }

  @Test
  void postTime_noTimeRows() {
    TimeGroup timeGroup = randomDataGenerator.randomTimeGroup();
    timeGroup.setTimeRows(Collections.emptyList());
    RuntimeConfig.setProperty(ConnectorConfigKey.CALLER_KEY, timeGroup.getCallerKey());

    assertThat(connector.postTime(null, timeGroup))
        .as("no time rows in time group")
        .isEqualTo(PostResult.PERMANENT_FAILURE.withMessage("Cannot post time group with no time rows"));

    verifyZeroInteractions(patriciaDao);
  }


  @Test
  void postTime_userNotFound() {
    TimeGroup timeGroup = randomDataGenerator.randomTimeGroup();
    RuntimeConfig.setProperty(ConnectorConfigKey.CALLER_KEY, timeGroup.getCallerKey());

    assertThat(connector.postTime(null, timeGroup))
        .as("no time rows in time group")
        .isEqualTo(PostResult.PERMANENT_FAILURE.withMessage("User does not exist: " + timeGroup.getUser().getExternalId()));
  }


  @Test
  void postTime_noDbDateLoaded() {
    TimeGroup timeGroup = randomDataGenerator.randomTimeGroup();
    RuntimeConfig.setProperty(ConnectorConfigKey.CALLER_KEY, timeGroup.getCallerKey());

    String userLogin = FAKER.internet().uuid();
    when(patriciaDao.findLoginByEmail(timeGroup.getUser().getExternalId())).thenReturn(Optional.of(userLogin));

    assertThat(connector.postTime(null, timeGroup))
        .as("failed to load database date")
        .isEqualTo(PostResult.PERMANENT_FAILURE.withMessage("Failed to get current database date"));
  }

  @Test
  void postTime_noCasesMatched() {
    TimeGroup timeGroup = randomDataGenerator.randomTimeGroup();
    RuntimeConfig.setProperty(ConnectorConfigKey.CALLER_KEY, timeGroup.getCallerKey());

    String userLogin = FAKER.internet().uuid();
    String dbDate = FAKER.date().birthday().toString();
    when(patriciaDao.findLoginByEmail(timeGroup.getUser().getExternalId())).thenReturn(Optional.of(userLogin));
    when(patriciaDao.getDbDate()).thenReturn(Optional.of(dbDate));

    assertThat(connector.postTime(null, timeGroup))
        .as("no cases matches tags")
        .isEqualTo(PostResult.SUCCESS.withMessage("Time group has no tags handled by integration."));
  }

  @Test
  void postTime_explicitNarrative() {
    RuntimeConfig.setProperty(ConnectorLauncher.PatriciaConnectorConfigKey.INVOICE_COMMENT_OVERRIDE, "custom_comment");
    TimeGroup timeGroup = randomDataGenerator.randomTimeGroup();
    RuntimeConfig.setProperty(ConnectorConfigKey.CALLER_KEY, timeGroup.getCallerKey());

    String userLogin = FAKER.internet().uuid();
    String dbDate = FAKER.date().birthday().toString();
    int caseId = FAKER.number().numberBetween(1, 10000);
    Tag tag = randomDataGenerator.randomTag();
    when(patriciaDao.findLoginByEmail(timeGroup.getUser().getExternalId())).thenReturn(Optional.of(userLogin));
    when(patriciaDao.getDbDate()).thenReturn(Optional.of(dbDate));
    when(patriciaDao.findCaseIds(timeGroup.getTags()))
        .thenReturn(ImmutableMap.of(caseId, tag));

    assertThat(connector.postTime(null, timeGroup))
        .as("no cases matches tags")
        .isEqualTo(PostResult.SUCCESS);

    PostTimeData expectedParams = ImmutablePostTimeData.builder()
        .caseId(caseId)
        .experienceWeightingPercent(timeGroup.getUser().getExperienceWeightingPercent())
        .loginId(userLogin)
        //see value of ConnectorLauncher.PatriciaConnectorConfigKey.TAG_MODIFIER_WORK_CODE_MAPPING
        .workCodeId("1")
        .recordalDate(dbDate)
        .caseName(tag.getName())
        .build();
    int expectedWorked = timeGroup.getTimeRows().stream().mapToInt(TimeRow::getDurationSecs).sum();
    int expectedChargeable = (int) Math
        .round(timeGroup.getTotalDurationSecs() * timeGroup.getUser().getExperienceWeightingPercent()
            / 100. / timeGroup.getTags().size());
    verify(patriciaDao, times(1))
        .calculateBilling(expectedParams, expectedChargeable, expectedWorked);
    verify(patriciaDao, times(1)).updateBudgetHeader(caseId);
    verify(patriciaDao, times(1))
        .addTimeRegistration(expectedParams, null, "custom_comment");
    verify(patriciaDao, times(1))
        .addBudgetLine(expectedParams, null, "custom_comment");
  }

  @Test
  void postTime_narrativeFromTemplateFormatter() {
    TimeGroup timeGroup = randomDataGenerator.randomTimeGroup();
    RuntimeConfig.setProperty(ConnectorConfigKey.CALLER_KEY, timeGroup.getCallerKey());

    String userLogin = FAKER.internet().uuid();
    String dbDate = FAKER.date().birthday().toString();
    int caseId = FAKER.number().numberBetween(1, 10000);
    Tag tag = randomDataGenerator.randomTag();
    when(patriciaDao.findLoginByEmail(timeGroup.getUser().getExternalId())).thenReturn(Optional.of(userLogin));
    when(patriciaDao.getDbDate()).thenReturn(Optional.of(dbDate));
    when(patriciaDao.findCaseIds(timeGroup.getTags()))
        .thenReturn(ImmutableMap.of(caseId, tag));

    assertThat(connector.postTime(null, timeGroup))
        .as("no cases matches tags")
        .isEqualTo(PostResult.SUCCESS);

    PostTimeData expectedParams = ImmutablePostTimeData.builder()
        .caseId(caseId)
        .experienceWeightingPercent(timeGroup.getUser().getExperienceWeightingPercent())
        .loginId(userLogin)
        //see value of ConnectorLauncher.PatriciaConnectorConfigKey.TAG_MODIFIER_WORK_CODE_MAPPING
        .workCodeId("1")
        .recordalDate(dbDate)
        .caseName(tag.getName())
        .build();
    int expectedWorked = timeGroup.getTimeRows().stream().mapToInt(TimeRow::getDurationSecs).sum();
    int expectedChargeable = (int) Math
        .round(timeGroup.getTotalDurationSecs() * timeGroup.getUser().getExperienceWeightingPercent()
            / 100. / timeGroup.getTags().size());
    verify(templateFormatter, times(1)).format(timeGroup);
    verify(patriciaDao, times(1))
        .calculateBilling(expectedParams, expectedChargeable, expectedWorked);
    verify(patriciaDao, times(1)).updateBudgetHeader(caseId);
    verify(patriciaDao, times(1))
        .addTimeRegistration(expectedParams, null, null);
    verify(patriciaDao, times(1))
        .addBudgetLine(expectedParams, null, null);
  }

}
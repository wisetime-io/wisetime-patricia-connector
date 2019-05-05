/*
 * Copyright (c) 2019 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.patricia;

import com.google.common.collect.ImmutableList;
import com.google.inject.Guice;
import com.google.inject.Injector;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import io.wisetime.connector.api_client.ApiClient;
import io.wisetime.connector.config.RuntimeConfig;
import io.wisetime.connector.datastore.ConnectorStore;
import io.wisetime.connector.integrate.ConnectorModule;
import io.wisetime.generated.connect.Tag;
import io.wisetime.generated.connect.TimeGroup;
import io.wisetime.generated.connect.TimeRow;
import io.wisetime.generated.connect.User;

import static org.mockito.Mockito.mock;

/**
 * Integration test for running a failed time group against the Patricia DB
 *
 * IMPORTANT: Do not forget to throw an exception at the end of the transaction in PatriciaConnector to trigger a rollback
 *
 * @author pascal.filippi@gmail.com
 */
@Disabled
class PatriciaConnectorIntegrationTest {

  private static PatriciaConnector connector;

  @BeforeAll
  static void setup() {
    RuntimeConfig.rebuild();
    RuntimeConfig.setProperty(ConnectorLauncher.PatriciaConnectorConfigKey.PATRICIA_JDBC_URL, "<change-me>");
    RuntimeConfig.setProperty(ConnectorLauncher.PatriciaConnectorConfigKey.PATRICIA_JDBC_USERNAME, "<change-me>");
    RuntimeConfig.setProperty(ConnectorLauncher.PatriciaConnectorConfigKey.PATRICIA_JDBC_PASSWORD, "<change-me>");

    // Set a role type id to use
    RuntimeConfig.setProperty(ConnectorLauncher.PatriciaConnectorConfigKey.PATRICIA_ROLE_TYPE_ID, "5");

    final Injector injector = Guice.createInjector(
        new ConnectorLauncher.PatriciaDbModule()
    );

    connector = injector.getInstance(PatriciaConnector.class);

    connector.init(new ConnectorModule(mock(ApiClient.class), mock(ConnectorStore.class)));
  }

  @Test
  void testFailedTimeGroup() {
    TimeGroup group = new TimeGroup();
    group.callerKey("Fehl8Szeterst")
        .groupId("5ccb6ec1e7862b0001c0d816")
        .description("")
        .totalDurationSecs(1452)
        .groupName("Blue")
        .narrativeType(TimeGroup.NarrativeTypeEnum.AND_TIME_ROW_ACTIVITY_DESCRIPTIONS)
        .tags(ImmutableList.of(new Tag().name("P1000EP00").path("Patricia").description("WATERSAFE PROTECTIVE CREAM")))
        .timeRows(ImmutableList.of(new TimeRow()
            .activity("Google Chrome")
            .description("New Tab - Google Chrome")
            .activityHour(2019050109)
            .firstObservedInHour(0)
            .durationSecs(233)
            .submittedDate(20190502222713606L)
            .source(TimeRow.SourceEnum.WT_DESKTOP),
            new TimeRow()
                .activity("Google Chrome")
                .description("PI Case Browser [P1000EP00] - Google Chrome")
                .activityHour(2019050109)
                .firstObservedInHour(5)
                .durationSecs(475)
                .submittedDate(20190502222713606L)
                .source(TimeRow.SourceEnum.WT_DESKTOP),
            new TimeRow()
                .activity("Google Chrome")
                .description("PI Case Browser [P1000EP00] - Google Chrome")
                .activityHour(2019050110)
                .firstObservedInHour(30)
                .durationSecs(744)
                .submittedDate(20190502222713606L)
                .source(TimeRow.SourceEnum.WT_DESKTOP)))
        .user(new User()
            .name("PI Demo")
            .email("pidemo@m.practiceinsight.io")
            .externalId("PPT")
            .businessRole("")
            .experienceWeightingPercent(100))
        .originatingUser(null)
        .durationSplitStrategy(TimeGroup.DurationSplitStrategyEnum.DIVIDE_BETWEEN_TAGS);
    connector.postTime(null, group);
  }
}

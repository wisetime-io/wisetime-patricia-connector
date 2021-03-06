/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.patricia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import com.github.javafaker.Faker;
import com.google.common.collect.ImmutableList;
import com.google.inject.Guice;
import io.wisetime.connector.ConnectorModule;
import io.wisetime.connector.ConnectorModule.IntervalConfig;
import io.wisetime.connector.api_client.ApiClient;
import io.wisetime.connector.config.RuntimeConfig;
import io.wisetime.connector.datastore.ConnectorStore;
import io.wisetime.generated.connect.UpsertTagRequest;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * @author vadym
 */
class PatriciaConnectorSyncNewCasesTest {

  private static RandomDataGenerator randomDataGenerator = new RandomDataGenerator();
  private static PatriciaDao patriciaDaoMock = mock(PatriciaDao.class);
  private static ApiClient apiClientMock = mock(ApiClient.class);
  private static ConnectorStore connectorStoreMock = mock(ConnectorStore.class);
  private static PatriciaConnector connector;

  @BeforeAll
  static void setUp() {
    RuntimeConfig.rebuild();

    // Set a role type id to use
    RuntimeConfig.setProperty(ConnectorLauncher.PatriciaConnectorConfigKey.PATRICIA_ROLE_TYPE_ID, "4");
    RuntimeConfig.setProperty(ConnectorLauncher.PatriciaConnectorConfigKey.PATRICIA_CASE_URL_PREFIX,
        new Faker().internet().url() + "/");
    connector = Guice.createInjector(binder -> {
      binder.bind(PatriciaDao.class).toProvider(() -> patriciaDaoMock);
    }).getInstance(PatriciaConnector.class);

    // Ensure PatriciaConnector#init will not fail
    doReturn(true).when(patriciaDaoMock).hasExpectedSchema();

    connector.init(new ConnectorModule(apiClientMock, connectorStoreMock,
        new IntervalConfig().setTagSlowLoopIntervalMinutes(5)
            .setActivityTypeSlowLoopIntervalMinutes(15)));
  }

  @BeforeEach
  void setUpTest() {
    reset(patriciaDaoMock);
    reset(apiClientMock);
    reset(connectorStoreMock);
  }

  @Test
  void syncNewCases_no_cases() {
    when(patriciaDaoMock.findCasesOrderById(anyLong(), anyInt())).thenReturn(ImmutableList.of());

    connector.syncNewCases();

    verifyZeroInteractions(apiClientMock);
    verify(connectorStoreMock, never()).putLong(anyString(), anyLong());
  }

  @Test
  void syncNewCases_upsert_error() throws IOException {
    when(patriciaDaoMock.findCasesOrderById(anyLong(), anyInt()))
        .thenReturn(ImmutableList.of(randomDataGenerator.randomCase(), randomDataGenerator.randomCase()));

    IOException casedBy = new IOException("Expected exception");
    doThrow(casedBy)
        .when(apiClientMock).tagUpsertBatch(anyList());

    assertThatThrownBy(() -> connector.syncNewCases())
        .isInstanceOf(RuntimeException.class)
        .hasCause(casedBy);
    verify(apiClientMock, times(1)).tagUpsertBatch(anyList());
    verify(connectorStoreMock, never()).putLong(anyString(), anyLong());
  }

  @Test
  void syncNewCases_new_cases_found() throws IOException {
    final PatriciaDao.Case case1 = randomDataGenerator.randomCase();
    final PatriciaDao.Case case2 = randomDataGenerator.randomCase();

    when(connectorStoreMock.getLong(anyString())).thenReturn(Optional.empty());

    ArgumentCaptor<Integer> batchSize = ArgumentCaptor.forClass(Integer.class);
    when(patriciaDaoMock.findCasesOrderById(anyLong(), batchSize.capture()))
        .thenReturn(ImmutableList.of(case1, case2))
        .thenReturn(ImmutableList.of());

    connector.syncNewCases();

    ArgumentCaptor<List<UpsertTagRequest>> upsertRequests = ArgumentCaptor.forClass(List.class);
    verify(apiClientMock, times(1)).tagUpsertBatch(upsertRequests.capture());

    assertThat(upsertRequests.getValue())
        .as("We should create tags for both new cases found, with the configured tag upsert path")
        .containsExactlyInAnyOrder(
            case1.toUpsertTagRequest("/Patricia/"),
            case2.toUpsertTagRequest("/Patricia/"));

    verify(connectorStoreMock, times(1))
        .putLong("patricia_last_sync_id", case2.caseId());
  }
}

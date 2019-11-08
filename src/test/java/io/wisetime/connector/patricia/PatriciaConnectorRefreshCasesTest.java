/*
 * Copyright (c) 2019 Practice Insight Pty Ltd. All Rights Reserved.
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

import com.google.common.collect.ImmutableList;
import com.google.inject.Guice;
import io.wisetime.connector.ConnectorModule;
import io.wisetime.connector.api_client.ApiClient;
import io.wisetime.connector.config.RuntimeConfig;
import io.wisetime.connector.datastore.ConnectorStore;
import io.wisetime.connector.patricia.ConnectorLauncher.PatriciaConnectorConfigKey;
import io.wisetime.generated.connect.UpsertTagRequest;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * @author yehor.lashkul
 */
class PatriciaConnectorRefreshCasesTest {

  private static RandomDataGenerator randomDataGenerator = new RandomDataGenerator();
  private static PatriciaDao patriciaDaoMock = mock(PatriciaDao.class);
  private static ApiClient apiClientMock = mock(ApiClient.class);
  private static ConnectorStore connectorStoreMock = mock(ConnectorStore.class);
  private static ConnectorModule connectorModule;
  private static PatriciaConnector connector;

  @BeforeAll
  static void setUp() {
    RuntimeConfig.rebuild();

    // Set a role type id to use
    RuntimeConfig.setProperty(ConnectorLauncher.PatriciaConnectorConfigKey.PATRICIA_ROLE_TYPE_ID, "4");

    connector = Guice.createInjector(binder ->
        binder.bind(PatriciaDao.class).toProvider(() -> patriciaDaoMock)
    ).getInstance(PatriciaConnector.class);

    // Ensure PatriciaConnector#init will not fail
    doReturn(true).when(patriciaDaoMock).hasExpectedSchema();

    connectorModule = new ConnectorModule(apiClientMock, connectorStoreMock);
    connector.init(connectorModule);
  }

  @BeforeEach
  void setUpTest() {
    reset(patriciaDaoMock);
    reset(apiClientMock);
    reset(connectorStoreMock);
  }

  @Test
  void refreshCases_no_cases() {
    when(patriciaDaoMock.findCasesOrderById(anyLong(), anyInt())).thenReturn(ImmutableList.of());

    connector.refreshCases(10);

    verifyZeroInteractions(apiClientMock);

    ArgumentCaptor<String> storeKey = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Long> storeValue = ArgumentCaptor.forClass(Long.class);
    verify(connectorStoreMock, times(1)).putLong(storeKey.capture(), storeValue.capture());

    assertThat(storeKey.getValue())
        .as("Last refreshed case key is as configured")
        .isEqualTo("patricia_last_refreshed_id");
    assertThat(storeValue.getValue())
        .as("Last refreshed ID should be reset to zero so that next batch will start over")
        .isEqualTo(0L);
  }

  @Test
  void refreshCases_upsert_error() throws IOException {
    when(patriciaDaoMock.findCasesOrderById(anyLong(), anyInt()))
        .thenReturn(ImmutableList.of(randomDataGenerator.randomCase(), randomDataGenerator.randomCase()));

    IOException casedBy = new IOException("Expected exception");
    doThrow(casedBy)
        .when(apiClientMock).tagUpsertBatch(anyList());

    assertThatThrownBy(() -> connector.refreshCases(10))
        .isInstanceOf(RuntimeException.class)
        .hasCause(casedBy);
    verify(apiClientMock, times(1)).tagUpsertBatch(anyList());
    verify(connectorStoreMock, never()).putLong(anyString(), anyLong());
  }

  @Test
  @SuppressWarnings("unchecked")
  void refreshCases_new_cases_found() throws IOException {
    final PatriciaDao.Case case1 = randomDataGenerator.randomCase();
    final PatriciaDao.Case case2 = randomDataGenerator.randomCase();

    when(connectorStoreMock.getLong(anyString())).thenReturn(Optional.empty());

    ArgumentCaptor<Integer> batchSize = ArgumentCaptor.forClass(Integer.class);
    when(patriciaDaoMock.findCasesOrderById(anyLong(), batchSize.capture()))
        .thenReturn(ImmutableList.of(case1, case2))
        .thenReturn(ImmutableList.of());

    connector.refreshCases(10);

    ArgumentCaptor<List<UpsertTagRequest>> upsertRequests = ArgumentCaptor.forClass(List.class);
    verify(apiClientMock, times(1)).tagUpsertBatch(upsertRequests.capture());

    assertThat(upsertRequests.getValue())
        .as("We should create tags for both new cases found, with the configured tag upsert path")
        .containsExactlyInAnyOrder(
            case1.toUpsertTagRequest("/Patricia/"),
            case2.toUpsertTagRequest("/Patricia/"));

    assertThat(batchSize.getValue())
        .as("The requested batch size should be used")
        .isEqualTo(10);

    verify(connectorStoreMock, times(1))
        .putLong("patricia_last_refreshed_id", case2.caseId());
  }

  @Test
  void tagRefreshBatchSize_enforce_min() {
    RuntimeConfig.setProperty(PatriciaConnectorConfigKey.TAG_UPSERT_BATCH_SIZE, "100");
    when(patriciaDaoMock.casesCount()).thenReturn(20L);
    assertThat(connector.tagRefreshBatchSize())
        .as("Calculated batch size was less than the minimum refresh batch size")
        .isEqualTo(10);
  }

  @Test
  void tagRefreshBatchSize_enforce_max() {
    RuntimeConfig.setProperty(PatriciaConnectorConfigKey.TAG_UPSERT_BATCH_SIZE, "20");
    when(patriciaDaoMock.casesCount()).thenReturn(Long.MAX_VALUE);
    assertThat(connector.tagRefreshBatchSize())
        .as("Calculated batch size was more than the maximum refresh batch size")
        .isEqualTo(20);
  }

  @Test
  void tagRefreshBatchSize_calculated() {
    RuntimeConfig.setProperty(PatriciaConnectorConfigKey.TAG_UPSERT_BATCH_SIZE, "1000");
    final int fourteenDaysInMinutes = 20_160;
    when(patriciaDaoMock.casesCount()).thenReturn(400_000L);
    assertThat(connector.tagRefreshBatchSize())
        .as("Calculated batch size was greater than the minimum and less than the maximum")
        .isEqualTo(400_000 / (fourteenDaysInMinutes / connectorModule.getTagSyncIntervalMinutes()));
  }
}

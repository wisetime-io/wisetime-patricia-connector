/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.patricia;

import com.google.common.collect.ImmutableList;
import com.google.inject.Guice;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import io.wisetime.connector.api_client.ApiClient;
import io.wisetime.connector.config.RuntimeConfig;
import io.wisetime.connector.datastore.ConnectorStore;
import io.wisetime.connector.integrate.ConnectorModule;
import io.wisetime.connector.template.TemplateFormatter;
import io.wisetime.generated.connect.UpsertTagRequest;

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

/**
 * @author vadym
 */
class PatriciaConnectorPerformTagUpdateTest {

  private static RandomDataGenerator randomDataGenerator = new RandomDataGenerator();
  private static PatriciaDao patriciaDao = mock(PatriciaDao.class);
  private static ApiClient apiClient = mock(ApiClient.class);
  private static ConnectorStore connectorStore = mock(ConnectorStore.class);
  private static PatriciaConnector connector;

  @BeforeAll
  static void setUp() {
    RuntimeConfig.rebuild();
    connector = Guice.createInjector(binder -> {
      binder.bind(PatriciaDao.class).toProvider(() -> patriciaDao);
    }).getInstance(PatriciaConnector.class);

    // Ensure PatriciaConnector#init will not fail
    doReturn(true).when(patriciaDao).isHealthy();

    connector.init(new ConnectorModule(apiClient, mock(TemplateFormatter.class), connectorStore));
  }

  @BeforeEach
  void setUpTest() {
    reset(patriciaDao);
    reset(apiClient);
    reset(connectorStore);
  }

  @Test
  void performTagUpdate_no_cases() {
    when(patriciaDao.findCasesOrderById(anyLong(), anyInt())).thenReturn(ImmutableList.of());

    connector.performTagUpdate();

    verifyZeroInteractions(apiClient);
    verify(connectorStore, never()).putLong(anyString(), anyLong());
  }

  @Test
  void performTagUpdate_upsert_error() throws IOException {
    when(patriciaDao.findCasesOrderById(anyLong(), anyInt()))
        .thenReturn(ImmutableList.of(randomDataGenerator.randomCase(), randomDataGenerator.randomCase()));

    IOException casedBy = new IOException("Expected exception");
    doThrow(casedBy)
        .when(apiClient).tagUpsertBatch(anyList());

    assertThatThrownBy(() -> connector.performTagUpdate())
        .isInstanceOf(RuntimeException.class)
        .hasCause(casedBy);
    verify(apiClient, times(1)).tagUpsertBatch(anyList());
    verify(connectorStore, never()).putLong(anyString(), anyLong());
  }

  @Test
  void performTagUpdate_new_cases_found() throws IOException {
    final PatriciaDao.PatriciaCase case1 = randomDataGenerator.randomCase();
    final PatriciaDao.PatriciaCase case2 = randomDataGenerator.randomCase();

    when(connectorStore.getLong(anyString())).thenReturn(Optional.empty());

    ArgumentCaptor<Integer> batchSize = ArgumentCaptor.forClass(Integer.class);
    when(patriciaDao.findCasesOrderById(anyLong(), batchSize.capture()))
        .thenReturn(ImmutableList.of(case1, case2))
        .thenReturn(ImmutableList.of());

    connector.performTagUpdate();

    ArgumentCaptor<List<UpsertTagRequest>> upsertRequests = ArgumentCaptor.forClass(List.class);
    verify(apiClient, times(1)).tagUpsertBatch(upsertRequests.capture());

    assertThat(upsertRequests.getValue())
        .containsExactlyInAnyOrder(case1.toUpsertTagRequest("/Patricia/"),
            case2.toUpsertTagRequest("/Patricia/"))
        .as("We should create tags for both new cases found, with the configured tag upsert path");

    verify(connectorStore, times(1))
        .putLong(PatriciaConnector.PATRICIA_LAST_SYNC_KEY, case2.getId());
  }
}
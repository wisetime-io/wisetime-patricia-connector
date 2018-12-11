/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.patricia;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.inject.Inject;

import io.wisetime.connector.api_client.ApiClient;
import io.wisetime.connector.api_client.PostResult;
import io.wisetime.connector.config.ConnectorConfigKey;
import io.wisetime.connector.config.RuntimeConfig;
import io.wisetime.connector.datastore.ConnectorStore;
import io.wisetime.connector.integrate.ConnectorModule;
import io.wisetime.connector.integrate.WiseTimeConnector;
import io.wisetime.connector.template.TemplateFormatter;
import io.wisetime.generated.connect.TimeGroup;
import io.wisetime.generated.connect.UpsertTagRequest;
import spark.Request;

import static io.wisetime.connector.patricia.ConnectorLauncher.PatriciaConnectorConfigKey;

/**
 * WiseTime Connector implementation for Patricia.
 *
 * @author vadym
 */
public class PatriciaConnector implements WiseTimeConnector {

  @VisibleForTesting
  static final String PATRICIA_LAST_SYNC_KEY = "patricia_last_sync_id";

  private ApiClient apiClient;
  private ConnectorStore connectorStore;
  private TemplateFormatter templateFormatter;

  @Inject
  private PatriciaDao patriciaDao;

  @Override
  public void init(final ConnectorModule connectorModule) {
    Preconditions.checkArgument(patriciaDao.isHealthy(),
        "Patricia Database connection is not healthy");

    this.apiClient = connectorModule.getApiClient();
    this.connectorStore = connectorModule.getConnectorStore();
    this.templateFormatter = connectorModule.getTemplateFormatter();
  }

  /**
   * Called by the WiseTime Connector library on a regular schedule.
   * Finds Patricia cases that haven't been synced and creates matching tags for them in WiseTime.
   */
  @Override
  public void performTagUpdate() {
    while (true) {
      final Optional<Long> storedLastSyncedCaseId = connectorStore.getLong(PATRICIA_LAST_SYNC_KEY);

      final List<PatriciaDao.PatriciaCase> newCases = patriciaDao.findCasesOrderById(
          storedLastSyncedCaseId.orElse(0L),
          tagUpsertBatchSize()
      );

      if (newCases.isEmpty()) {
        return;
      } else {
        try {
          final List<UpsertTagRequest> upsertRequests = newCases
              .stream()
              .map(item -> item.toUpsertTagRequest(tagUpsertPath()))
              .collect(Collectors.toList());

          apiClient.tagUpsertBatch(upsertRequests);

          final long lastSyncedCaseId = newCases.get(newCases.size() - 1).getId();
          connectorStore.putLong(PATRICIA_LAST_SYNC_KEY, lastSyncedCaseId);

        } catch (IOException e) {
          // The batch will be retried since we didn't update the last synced case ID
          // Let scheduler know that this batch has failed
          throw new RuntimeException(e);
        }
      }
    }
  }

  /**
   * Called by the WiseTime Connector library whenever a user posts time to the team.
   * Registers worked time and updates budget if needed.
   */
  @Override
  public PostResult postTime(final Request request, final TimeGroup userPostedTime) {
    //todo (vs)
    return PostResult.PERMANENT_FAILURE;
  }

  @Override
  public boolean isConnectorHealthy() {
    return patriciaDao.isHealthy();
  }

  private int tagUpsertBatchSize() {
    return RuntimeConfig
        .getInt(PatriciaConnectorConfigKey.TAG_UPSERT_BATCH_SIZE)
        // A large batch mitigates query round trip latency
        .orElse(500);
  }

  private String tagUpsertPath() {
    return RuntimeConfig
        .getString(PatriciaConnectorConfigKey.TAG_UPSERT_PATH)
        .orElse("/Patricia/");
  }

  private Optional<String> callerKey() {
    return RuntimeConfig.getString(ConnectorConfigKey.CALLER_KEY);
  }
}

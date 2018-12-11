/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.patricia;

import com.google.common.base.Preconditions;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
import io.wisetime.generated.connect.Tag;
import io.wisetime.generated.connect.TimeGroup;
import io.wisetime.generated.connect.TimeRow;
import io.wisetime.generated.connect.UpsertTagRequest;
import spark.Request;

import static io.wisetime.connector.patricia.ConnectorLauncher.PatriciaConnectorConfigKey;
import static io.wisetime.connector.patricia.PatriciaDao.BillingData;
import static io.wisetime.connector.patricia.PatriciaDao.Case;
import static io.wisetime.connector.patricia.PatriciaDao.PostTimeData;
import static io.wisetime.connector.utils.TagDurationCalculator.tagDuration;

/**
 * WiseTime Connector implementation for Patricia.
 *
 * @author vadym
 */
public class PatriciaConnector implements WiseTimeConnector {

  private static final Logger log = LoggerFactory.getLogger(PatriciaConnector.class);

  static final String PATRICIA_LAST_SYNC_KEY = "patricia_last_sync_id";

  private ApiClient apiClient;
  private ConnectorStore connectorStore;
  private TemplateFormatter templateFormatter;

  private String defaultModifier;
  private Map<String, String> modifierWorkCodeMap;

  @Inject
  private PatriciaDao patriciaDao;

  @Override
  public void init(final ConnectorModule connectorModule) {
    Preconditions.checkArgument(patriciaDao.isHealthy(),
        "Patricia Database connection is not healthy");
    initializeModifiers();

    this.apiClient = connectorModule.getApiClient();
    this.connectorStore = connectorModule.getConnectorStore();
    this.templateFormatter = connectorModule.getTemplateFormatter();
  }

  private void initializeModifiers() {
    defaultModifier = RuntimeConfig.getString(PatriciaConnectorConfigKey.DEFAULT_MODIFIER)
        .orElseThrow(() -> new IllegalStateException("Required configuration DEFAULT_MODIFIER is not set"));

    modifierWorkCodeMap =
        Arrays.stream(
            RuntimeConfig.getString(PatriciaConnectorConfigKey.TAG_MODIFIER_MAPPINGS)
              .orElseThrow(() ->
                  new IllegalStateException("Required configuration TAG_MODIFIER_PATRICIA_WORK_CODE_MAPPINGS is not set"))
              .split(","))
            .map(tagModifierMapping -> {
              String[] modifierAndWorkCode = tagModifierMapping.trim().split(":");
              if (modifierAndWorkCode.length != 2) {
                throw new IllegalStateException("Invalid patricia modifier to work code mapping. "
                    + "Expecting modifier:workCode, got: " + tagModifierMapping);
              }
              return modifierAndWorkCode;
            })
            .collect(Collectors.toMap(
                modifierWorkCodePair -> modifierWorkCodePair[0],
                modifierWorkCodePair -> modifierWorkCodePair[1])
            );

    Preconditions.checkArgument(modifierWorkCodeMap.containsKey(defaultModifier),
        "Patricia modifiers mapping should include work code for default modifier");
  }

  /**
   * Called by the WiseTime Connector library on a regular schedule.
   * Finds Patricia cases that haven't been synced and creates matching tags for them in WiseTime.
   */
  @Override
  public void performTagUpdate() {
    while (true) {
      final Optional<Long> storedLastSyncedCaseId = connectorStore.getLong(PATRICIA_LAST_SYNC_KEY);

      final List<Case> newCases = patriciaDao.findCasesOrderById(
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
    Optional<String> callerKeyOpt = callerKey();
    if (callerKeyOpt.isPresent() && !callerKeyOpt.get().equals(userPostedTime.getCallerKey())) {
      return PostResult.PERMANENT_FAILURE
          .withMessage("Invalid caller key in post time webhook call");
    }

    if (userPostedTime.getTags().isEmpty()) {
      return PostResult.SUCCESS
          .withMessage("Time group has no tags. There is nothing to post to Patricia.");
    }

    if (userPostedTime.getTimeRows().isEmpty()) {
      return PostResult.PERMANENT_FAILURE
          .withMessage("Cannot post time group with no time rows");
    }

    final Optional<String> userLogin = patriciaDao.findLoginByEmail(userPostedTime.getUser().getExternalId());
    if (!userLogin.isPresent()) {
      return PostResult.PERMANENT_FAILURE
          .withMessage("User does not exist: " + userPostedTime.getUser().getExternalId());
    }

    final Optional<String> dbDate = patriciaDao.getDbDate();
    if (!dbDate.isPresent()) {
      return PostResult.PERMANENT_FAILURE.withMessage("Failed to get current database date");
    }

    final Map<Integer, Tag> caseIdTagMap = patriciaDao.findCaseIds(userPostedTime.getTags());

    if (caseIdTagMap.isEmpty()) {
      return PostResult.SUCCESS
          .withMessage("Time group has no tags handled by integration.");
    }

    final int workedTime = (int) Math.round(tagDuration(userPostedTime));
    final String workCode = getTimeGroupWorkCode(userPostedTime.getTimeRows());
    final int actualWorkedTimeSecs = userPostedTime.getTimeRows().stream()
        .mapToInt(TimeRow::getDurationSecs)
        .sum();

    //todo (vs) internal & public template logic
    final String comment = RuntimeConfig.getString(PatriciaConnectorConfigKey.PATRICIA_TIME_COMMENT_INVOICE)
        .orElseGet(() -> templateFormatter.format(userPostedTime));
    try {
      patriciaDao.asTransaction(() ->
          caseIdTagMap.forEach((caseId, tag) -> {
            final PostTimeData commonParams = ImmutablePostTimeData.builder()
                .caseId(caseId)
                .experienceWeightingPercent(userPostedTime.getUser().getExperienceWeightingPercent())
                .loginId(userLogin.get())
                .workCodeId(workCode)
                .recordalDate(dbDate.get())
                .caseName(tag.getName())
                .build();
            final BillingData billingData = patriciaDao.calculateBilling(
                commonParams, workedTime, actualWorkedTimeSecs
            );

            patriciaDao.updateBudgetHeader(caseId);
            patriciaDao.addTimeRegistration(commonParams, billingData, comment);
            patriciaDao.addBudgetLine(commonParams, billingData, comment);
          })
      );
    } catch (RuntimeException e) {
      log.warn("Failed to save posted time in Patricia", e);
      return PostResult.TRANSIENT_FAILURE
          .withError(e)
          .withMessage("There was an error posting time to the Patricia database");
    }
    return PostResult.SUCCESS;
  }

  private String getTimeGroupWorkCode(final List<TimeRow> timeRows) {
    final List<String> workCodes = timeRows.stream()
        .map(TimeRow::getModifier)
        .map(modifier -> StringUtils.defaultIfEmpty(modifier, defaultModifier))
        .distinct()
        .map(modifierWorkCodeMap::get)
        .collect(Collectors.toList());
    if (workCodes.size() != 1) {
      throw new IllegalStateException("All time logs within time group should have same modifier, but got:"
          + timeRows.stream().map(TimeRow::getModifier).distinct().collect(Collectors.toList()));
    }
    return workCodes.get(0);
  }

  private Optional<String> callerKey() {
    return RuntimeConfig.getString(ConnectorConfigKey.CALLER_KEY);
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

  @Override
  public boolean isConnectorHealthy() {
    return patriciaDao.isHealthy();
  }
}

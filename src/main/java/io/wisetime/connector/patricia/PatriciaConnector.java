/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.patricia;

import com.google.common.base.Preconditions;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.inject.Inject;

import io.wisetime.connector.api_client.ApiClient;
import io.wisetime.connector.api_client.PostResult;
import io.wisetime.connector.config.ConnectorConfigKey;
import io.wisetime.connector.config.RuntimeConfig;
import io.wisetime.connector.datastore.ConnectorStore;
import io.wisetime.connector.integrate.ConnectorModule;
import io.wisetime.connector.integrate.WiseTimeConnector;
import io.wisetime.connector.patricia.util.ChargeCalculator;
import io.wisetime.connector.template.TemplateFormatter;
import io.wisetime.connector.template.TemplateFormatterConfig;
import io.wisetime.generated.connect.Tag;
import io.wisetime.generated.connect.TimeGroup;
import io.wisetime.generated.connect.TimeRow;
import io.wisetime.generated.connect.UpsertTagRequest;
import spark.Request;

import static io.wisetime.connector.patricia.ConnectorLauncher.PatriciaConnectorConfigKey;
import static io.wisetime.connector.patricia.PatriciaDao.BudgetLine;
import static io.wisetime.connector.patricia.PatriciaDao.Case;
import static io.wisetime.connector.patricia.PatriciaDao.Discount;
import static io.wisetime.connector.patricia.PatriciaDao.TimeRegistration;
import static io.wisetime.connector.utils.ActivityTimeCalculator.startTime;

/**
 * WiseTime Connector implementation for Patricia.
 *
 * @author vadym
 */
public class PatriciaConnector implements WiseTimeConnector {

  private static final Logger log = LoggerFactory.getLogger(PatriciaConnector.class);
  private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  static final String PATRICIA_LAST_SYNC_KEY = "patricia_last_sync_id";

  private ApiClient apiClient;
  private ConnectorStore connectorStore;
  private TemplateFormatter timeRegistrationTemplate;
  private TemplateFormatter chargeTemplate;

  private String defaultModifier;
  private Map<String, String> modifierWorkCodeMap;
  private int roleTypeId;

  @Inject
  private PatriciaDao patriciaDao;

  @Override
  public void init(final ConnectorModule connectorModule) {
    Preconditions.checkArgument(patriciaDao.hasExpectedSchema(),
        "Patricia Database schema is unsupported by this connector");
    initializeModifiers();
    initializeRoleTypeId();
    initializeTemplateFormatters();

    this.apiClient = connectorModule.getApiClient();
    this.connectorStore = connectorModule.getConnectorStore();
  }

  private void initializeRoleTypeId() {
    this.roleTypeId = RuntimeConfig.getInt(PatriciaConnectorConfigKey.PATRICIA_ROLE_TYPE_ID)
        .orElseThrow(() -> new IllegalStateException("Required configuration param PATRICIA_ROLE_TYPE_ID is not set."));
  }

  /**
   * Called by the WiseTime Connector library on a regular schedule to check if Connectos is healthy.
   */
  @Override
  public boolean isConnectorHealthy() {
    return patriciaDao.canQueryDbDate();
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
        log.info("No new tags found. Last case ID synced: {}", storedLastSyncedCaseId);
        return;
      } else {
        try {
          log.info("Detected {} new {}: {}",
              newCases.size(),
              newCases.size() > 1 ? "tags" : "tag",
              newCases.stream().map(Case::caseNumber).collect(Collectors.joining(", ")));

          final List<UpsertTagRequest> upsertRequests = newCases
              .stream()
              .map(item -> item.toUpsertTagRequest(tagUpsertPath()))
              .collect(Collectors.toList());

          apiClient.tagUpsertBatch(upsertRequests);

          final long lastSyncedCaseId = newCases.get(newCases.size() - 1).caseId();
          connectorStore.putLong(PATRICIA_LAST_SYNC_KEY, lastSyncedCaseId);
          log.info("Last synced case ID: {}", lastSyncedCaseId);
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
    log.info("Posted time received for {}: {}",
        userPostedTime.getUser().getExternalId(),
        Base64.getEncoder().encodeToString(userPostedTime.toString().getBytes()));

    Optional<String> callerKeyOpt = callerKey();
    if (callerKeyOpt.isPresent() && !callerKeyOpt.get().equals(userPostedTime.getCallerKey())) {
      return PostResult.PERMANENT_FAILURE.withMessage("Invalid caller key in post time webhook call");
    }

    if (userPostedTime.getTags().isEmpty()) {
      return PostResult.SUCCESS.withMessage("Time group has no tags. There is nothing to post to Patricia.");
    }

    if (userPostedTime.getTimeRows().isEmpty()) {
      return PostResult.PERMANENT_FAILURE.withMessage("Cannot post time group with no time rows");
    }

    final Optional<String> workCode = getTimeGroupWorkCode(userPostedTime.getTimeRows());
    if (!workCode.isPresent()) {
      return PostResult.PERMANENT_FAILURE.withMessage("Time group contains invalid modifier.");
    }

    final Optional<String> user = patriciaDao.findLoginByEmail(userPostedTime.getUser().getExternalId());
    if (!user.isPresent()) {
      return PostResult.PERMANENT_FAILURE.withMessage("User does not exist: " + userPostedTime.getUser().getExternalId());
    }

    final Function<Tag, Optional<Case>> findCase = tag -> {
      final Optional<Case> issue = patriciaDao.findCaseByCaseNumber(tag.getName());
      if (!issue.isPresent()) {
        log.warn("Can't find Patricia case for tag {}. No time will be posted for this tag.", tag.getName());
      }
      return issue;
    };

    final Optional<BigDecimal> hourlyRate = patriciaDao.findUserHourlyRate(workCode.get(), user.get());
    if (!hourlyRate.isPresent()) {
      return PostResult.PERMANENT_FAILURE.withMessage("No hourly rate is found for " + user.get());
    }

    final Optional<LocalDateTime> activityStartTime = startTime(userPostedTime);
    if (!activityStartTime.isPresent()) {
      return PostResult.PERMANENT_FAILURE.withMessage("Cannot post time group with no time rows");
    }

    final BigDecimal actualWorkedHoursPerCase =
        ChargeCalculator.calculateActualWorkedHoursNoExpRatingPerCase(userPostedTime);
    final BigDecimal actualWorkedHoursWithExpRatingPerCase =
        ChargeCalculator.calculateActualWorkedHoursWithExpRatingPerCase(userPostedTime);

    final BigDecimal chargeableHoursPerCase =
        ChargeCalculator.calculateChargeableWorkedHoursNoExpRatingPerCase(userPostedTime);
    final BigDecimal chargeableHoursWithExpRatingPerCase =
        ChargeCalculator.calculateChargeableWorkedHoursWithExpRatingPerCase(userPostedTime);

    final Optional<String> commentOverride = RuntimeConfig.getString(PatriciaConnectorConfigKey.INVOICE_COMMENT_OVERRIDE);

    final TimeGroup timeGroupToFormat = convertToZone(userPostedTime, getTimeZoneId());
    final String timeRegComment =  commentOverride.orElse(timeRegistrationTemplate.format(timeGroupToFormat));
    final String chargeComment = commentOverride.orElse(chargeTemplate.format(timeGroupToFormat));

    final Consumer<Case> createTimeAndChargeRecord = patriciaCase ->
        executeCreateTimeAndChargeRecord(ImmutableCreateTimeAndChargeParams.builder()
            .patriciaCase(patriciaCase)
            .workCode(workCode.get())
            .userId(user.get())
            .timeRegComment(timeRegComment)
            .chargeComment(chargeComment)
            .hourlyRate(hourlyRate.get())
            .actualHoursNoExpRating(actualWorkedHoursPerCase)
            .actualHoursWithExpRating(actualWorkedHoursWithExpRatingPerCase)
            .chargeableHoursNoExpRating(chargeableHoursPerCase)
            .chargeableHoursWithExpRating(chargeableHoursWithExpRatingPerCase)
            .recordalDate(activityStartTime.get())
            .build()
    );

    try {
      patriciaDao.asTransaction(() ->
          userPostedTime
          .getTags()
          .stream()

          .map(findCase)
          .filter(Optional::isPresent)
          .map(Optional::get)

          .forEach(createTimeAndChargeRecord)
      );
    } catch (RuntimeException e) {
      log.warn("Failed to save posted time in Patricia", e);
      return PostResult.TRANSIENT_FAILURE
          .withError(e)
          .withMessage("There was an error posting time to the Patricia database");
    }
    return PostResult.SUCCESS;
  }

  private void initializeModifiers() {
    defaultModifier = RuntimeConfig.getString(PatriciaConnectorConfigKey.DEFAULT_MODIFIER)
        .orElseThrow(() -> new IllegalStateException("Required configuration param DEFAULT_MODIFIER is not set."));

    modifierWorkCodeMap =
        Arrays.stream(
            RuntimeConfig.getString(PatriciaConnectorConfigKey.TAG_MODIFIER_WORK_CODE_MAPPING)
                .orElseThrow(() ->
                    new IllegalStateException(
                        "Required configuration param TAG_MODIFIER_PATRICIA_WORK_CODE_MAPPINGS is not set."
                    )
                )
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

  private Optional<String> getTimeGroupWorkCode(final List<TimeRow> timeRows) {
    final List<String> workCodes = timeRows.stream()
        .map(TimeRow::getModifier)
        .map(modifier -> StringUtils.defaultIfEmpty(modifier, defaultModifier))
        .distinct()
        .map(modifierWorkCodeMap::get)
        .collect(Collectors.toList());
    if (workCodes.size() != 1) {
      log.error(
          "All time logs within time group should have same modifier, but got: {}",
          timeRows.stream().map(TimeRow::getModifier).distinct().collect(Collectors.toList())
      );
      return Optional.empty();
    }
    return Optional.of(workCodes.get(0));
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

  private void executeCreateTimeAndChargeRecord(PatriciaDao.CreateTimeAndChargeParams params) {
    final String dbDate = patriciaDao.getDbDate();

    final String currency = patriciaDao.findCurrency(params.patriciaCase().caseId(), roleTypeId)
        .orElseThrow(() -> new RuntimeException(
            "Could not find external system currency for case " + params.patriciaCase().caseNumber())
        );

    final List<Discount> discounts = patriciaDao.findDiscounts(
        params.workCode(), roleTypeId, params.patriciaCase().caseId()
    );
    final Optional<Discount> discount = ChargeCalculator.getMostApplicableDiscount(discounts, params.patriciaCase());

    TimeRegistration timeRegistration = ImmutableTimeRegistration.builder()
        .caseId(params.patriciaCase().caseId())
        .workCodeId(params.workCode())
        .userId(params.userId())
        .submissionDate(dbDate)
        .activityDate(ZonedDateTime.of(params.recordalDate(), ZoneOffset.UTC)
            .withZoneSameInstant(getTimeZoneId())
            .format(DATE_TIME_FORMATTER))
        .actualHours(params.actualHoursNoExpRating())
        .chargeableHours(params.chargeableHoursNoExpRating())
        .comment(params.timeRegComment())
        .build();

    BigDecimal actualWorkTotalAmount = params.chargeableHoursNoExpRating().multiply(params.hourlyRate());
    BigDecimal chargeWithoutDiscount = params.chargeableHoursWithExpRating().multiply(params.hourlyRate());
    BigDecimal chargeWithDiscount = ChargeCalculator.calculateTotalCharge(
        discount, params.chargeableHoursWithExpRating(), params.hourlyRate()
    );

    BudgetLine budgetLine = ImmutableBudgetLine.builder()
        .caseId(params.patriciaCase().caseId())
        .workCodeId(params.workCode())
        .userId(params.userId())
        .submissionDate(dbDate)
        .currency(currency)
        .hourlyRate(params.hourlyRate())
        .actualWorkTotalHours(params.actualHoursWithExpRating())
        .chargeableWorkTotalHours(params.chargeableHoursWithExpRating())
        .chargeableAmount(chargeWithDiscount)
        .actualWorkTotalAmount(actualWorkTotalAmount)
        .discountAmount(ChargeCalculator.calculateDiscountAmount(chargeWithoutDiscount, chargeWithDiscount))
        .discountPercentage(ChargeCalculator.calculateDiscountPercentage(chargeWithoutDiscount, chargeWithDiscount))
        .effectiveHourlyRate(ChargeCalculator.calculateHourlyRate(chargeWithDiscount, params.chargeableHoursWithExpRating()))
        .comment(params.chargeComment())
        .build();

    patriciaDao.updateBudgetHeader(params.patriciaCase().caseId(), dbDate);
    patriciaDao.addTimeRegistration(timeRegistration);
    patriciaDao.addBudgetLine(budgetLine);

    log.info("Posted time to Patricia issue {} on behalf of {}", params.patriciaCase().caseNumber(), params.userId());
  }

  private void initializeTemplateFormatters() {
    boolean includeTimeDuration =
        RuntimeConfig.getString(ConnectorLauncher.PatriciaConnectorConfigKey.INCLUDE_DURATIONS_IN_INVOICE_COMMENT)
            .map(Boolean::parseBoolean)
            .orElse(false);

    if (includeTimeDuration) {
      timeRegistrationTemplate = createTemplateFormatter("classpath:patricia-with-duration_time-registration.ftl");
      chargeTemplate = createTemplateFormatter("classpath:patricia-with-duration_charge.ftl");
    } else {
      timeRegistrationTemplate = createTemplateFormatter("classpath:patricia-no-duration_time-registration.ftl");
      chargeTemplate = createTemplateFormatter("classpath:patricia-no-duration_charge.ftl");
    }
  }

  private TemplateFormatter createTemplateFormatter(String getTemplatePath) {
    return new TemplateFormatter(TemplateFormatterConfig.builder()
        .withTemplatePath(getTemplatePath)
        .build());
  }

  private ZoneId getTimeZoneId() {
    return ZoneId.of(RuntimeConfig.getString(PatriciaConnectorConfigKey.TIMEZONE).orElse("UTC"));
  }

  private TimeGroup convertToZone(TimeGroup timeGroup, ZoneId zoneId) {
    return new TimeGroup()
        .callerKey(timeGroup.getCallerKey())
        .groupId(timeGroup.getGroupId())
        .description(timeGroup.getDescription())
        .totalDurationSecs(timeGroup.getTotalDurationSecs())
        .groupName(timeGroup.getGroupName())
        .narrativeType(timeGroup.getNarrativeType())
        .tags(timeGroup.getTags())
        .user(timeGroup.getUser())
        .durationSplitStrategy(timeGroup.getDurationSplitStrategy())
        .timeRows(timeGroup.getTimeRows()
            .stream()
            .map(tr -> convertToZone(tr, zoneId))
            .collect(Collectors.toList())
        );
  }

  private TimeRow convertToZone(TimeRow timeRow, ZoneId zoneId) {
    final Pair<Integer, Integer> activityTimePair = convertToZone(
        timeRow.getActivityHour(), timeRow.getFirstObservedInHour(), zoneId
    );

    return new TimeRow()
        .activity(timeRow.getActivity())
        .description(timeRow.getDescription())
        .durationSecs(timeRow.getDurationSecs())
        .submittedDate(timeRow.getSubmittedDate())
        .modifier(timeRow.getModifier())
        .source(timeRow.getSource())
        .activityHour(activityTimePair.getLeft())
        .firstObservedInHour(activityTimePair.getRight());
  }

  private Pair<Integer, Integer> convertToZone(int activityHour, int firstObservedInHour, ZoneId toZoneId) {
    final DateTimeFormatter activityTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    final String activityTimeUTC = activityHour + StringUtils.leftPad(String.valueOf(firstObservedInHour), 2, "0");
    final String activityTimeConverted = ZonedDateTime
        .of(LocalDateTime.parse(activityTimeUTC, activityTimeFormatter), ZoneOffset.UTC)
        .withZoneSameInstant(toZoneId)
        .format(activityTimeFormatter);

    return Pair.of(
        Integer.parseInt(activityTimeConverted.substring(0, 10)), // yyyyMMddHH
        Integer.parseInt(activityTimeConverted.substring(10))     // mm
    );
  }
}

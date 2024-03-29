/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.patricia;

import static io.wisetime.connector.patricia.ConnectorLauncher.PatriciaConnectorConfigKey;
import static io.wisetime.connector.patricia.PatriciaDao.BudgetLine;
import static io.wisetime.connector.patricia.PatriciaDao.Case;
import static io.wisetime.connector.patricia.PatriciaDao.Discount;
import static io.wisetime.connector.patricia.PatriciaDao.TimeRegistration;
import static io.wisetime.connector.utils.ActivityTimeCalculator.startInstant;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import io.wisetime.connector.ConnectorModule;
import io.wisetime.connector.WiseTimeConnector;
import io.wisetime.connector.api_client.ApiClient;
import io.wisetime.connector.api_client.PostResult;
import io.wisetime.connector.config.RuntimeConfig;
import io.wisetime.connector.datastore.ConnectorStore;
import io.wisetime.connector.patricia.PatriciaDao.CreateTimeAndChargeParams;
import io.wisetime.connector.patricia.PatriciaDao.RateCurrency;
import io.wisetime.connector.patricia.PatriciaDao.WorkCode;
import io.wisetime.connector.patricia.util.ChargeCalculator;
import io.wisetime.connector.patricia.util.ConnectorException;
import io.wisetime.connector.patricia.util.HashFunction;
import io.wisetime.connector.template.TemplateFormatter;
import io.wisetime.connector.template.TemplateFormatterConfig;
import io.wisetime.connector.template.TemplateFormatterConfig.DisplayZone;
import io.wisetime.generated.connect.ActivityType;
import io.wisetime.generated.connect.DeleteTagRequest;
import io.wisetime.generated.connect.SyncActivityTypesRequest;
import io.wisetime.generated.connect.SyncSession;
import io.wisetime.generated.connect.Tag;
import io.wisetime.generated.connect.TimeGroup;
import io.wisetime.generated.connect.TimeRow;
import io.wisetime.generated.connect.UpsertTagRequest;
import io.wisetime.generated.connect.User;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.inject.Inject;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WiseTime Connector implementation for Patricia.
 *
 * @author vadym
 */
public class PatriciaConnector implements WiseTimeConnector {

  private static final Logger log = LoggerFactory.getLogger(PatriciaConnector.class);
  private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

  private static final String PATRICIA_LAST_SYNC_KEY = "patricia_last_sync_id";
  private static final String PATRICIA_LAST_REFRESHED_KEY = "patricia_last_refreshed_id";
  static final String PATRICIA_WORK_CODES_HASH_KEY = "patricia_work_codes_hash";
  static final String PATRICIA_WORK_CODES_LAST_SYNC_KEY = "patricia_work_codes_last_sync";
  static final int WORK_CODES_BATCH_SIZE = 500;

  private Supplier<Integer> tagSyncIntervalMinutes;
  private ApiClient apiClient;
  private ConnectorStore connectorStore;
  private TemplateFormatter timeRegistrationTemplate;
  private TemplateFormatter chargeTemplate;

  private Set<String> zeroChargeWorkCodes;
  private int roleTypeId;

  @Inject
  private HashFunction hashFunction;

  @Inject
  private PatriciaDao patriciaDao;

  @Override
  public void init(final ConnectorModule connectorModule) {
    Preconditions.checkArgument(patriciaDao.hasExpectedSchema(),
        "Patricia Database schema is unsupported by this connector");
    zeroChargeWorkCodes = Arrays.stream(
        RuntimeConfig.getString(PatriciaConnectorConfigKey.WORK_CODES_ZERO_CHARGE).orElse("").split(","))
        .map(String::trim)
        .filter(workCode -> !workCode.isEmpty())
        .collect(Collectors.toSet());
    initializeRoleTypeId();

    // default to no summary
    if (RuntimeConfig.getBoolean(PatriciaConnectorConfigKey.ADD_SUMMARY_TO_NARRATIVE).orElse(false)) {
      timeRegistrationTemplate = createTemplateFormatter(
          "classpath:narrative-template/patricia-template_time-registration.ftl");
    } else {
      // in case of no summary, just use the charge template, as it is the same as time registration without summary
      timeRegistrationTemplate = createTemplateFormatter(
          "classpath:narrative-template/patricia-template_charge.ftl");
    }
    chargeTemplate = createTemplateFormatter(
        "classpath:narrative-template/patricia-template_charge.ftl");

    tagSyncIntervalMinutes = connectorModule::getTagSlowLoopIntervalMinutes;
    apiClient = connectorModule.getApiClient();
    connectorStore = connectorModule.getConnectorStore();
  }

  private void initializeRoleTypeId() {
    roleTypeId = RuntimeConfig.getInt(PatriciaConnectorConfigKey.PATRICIA_ROLE_TYPE_ID)
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
   *
   * Finds all Patricia cases that haven't been synced and creates matching tags for them in WiseTime.
   * Blocks until all cases have been synced.
   */
  @Override
  public void performTagUpdate() {
    syncNewCases();
  }

  /**
   * Sends a batch of already synced cases to WiseTime to maintain freshness of existing tags.
   * Mitigates effect of renamed or missed tags.
   */
  @Override
  public void performTagUpdateSlowLoop() {
    refreshCases(tagRefreshBatchSize());
  }

  @Override
  public void performActivityTypeUpdate() {
    syncWorkCodes();
  }

  @Override
  public void performActivityTypeUpdateSlowLoop() {
    // no slow loop is needed as `performActivityTypeUpdate` will handle any change
  }

  /**
   * Called by the WiseTime Connector library whenever a user posts time to the team.
   * Registers worked time and updates budget if needed.
   */
  @Override
  public PostResult postTime(final TimeGroup userPostedTime) {
    log.info("Posted time received: {}", userPostedTime.getGroupId());

    List<Tag> relevantTags = getRelevantTags(userPostedTime);
    userPostedTime.setTags(relevantTags);

    if (userPostedTime.getTags().isEmpty()) {
      return PostResult.SUCCESS().withMessage("Time group has no tags. There is nothing to post to Patricia.");
    }

    if (userPostedTime.getTimeRows().isEmpty()) {
      return PostResult.PERMANENT_FAILURE().withMessage("Cannot post time group with no time rows");
    }

    final Optional<String> workCode = getTimeGroupWorkCode(userPostedTime);
    if (workCode.isEmpty()) {
      return PostResult.PERMANENT_FAILURE().withMessage("Time group contains invalid modifier.");
    }

    final Optional<String> user = getPatriciaLoginId(userPostedTime.getUser());
    if (user.isEmpty()) {
      return PostResult.PERMANENT_FAILURE().withMessage("User does not exist: " + userPostedTime.getUser().getExternalId());
    }

    final Optional<Instant> activityStartTime = startInstant(userPostedTime);
    if (activityStartTime.isEmpty()) {
      return PostResult.PERMANENT_FAILURE().withMessage("Cannot post time group with no time rows");
    }

    // If we recognize a zero charge work code: Set chargeable amount to 0 and let the calculation run as usual
    if (zeroChargeWorkCodes.contains(workCode.get())) {
      userPostedTime.totalDurationSecs(0);
    }

    final BigDecimal actualWorkedHoursPerCase =
        ChargeCalculator.calculateActualWorkedHoursNoExpRating(userPostedTime);

    final BigDecimal chargeableHoursPerCase;
    if (ChargeCalculator.wasTotalDurationEdited(userPostedTime)) {
      // time was edited -> use the edited time as is (no exp rating)
      chargeableHoursPerCase = ChargeCalculator.calculateChargeableWorkedHoursNoExpRating(userPostedTime);
    } else {
      // time was not edited -> use the experience rating
      chargeableHoursPerCase = ChargeCalculator.calculateChargeableWorkedHoursWithExpRating(userPostedTime);
    }

    final Optional<String> commentOverride = RuntimeConfig.getString(PatriciaConnectorConfigKey.INVOICE_COMMENT_OVERRIDE);

    final String timeRegComment = commentOverride.orElse(timeRegistrationTemplate.format(userPostedTime));
    final String chargeComment = commentOverride.orElse(chargeTemplate.format(userPostedTime));

    final Consumer<Case> createTimeAndChargeRecord = patriciaCase ->
        executeCreateTimeAndChargeRecord(CreateTimeAndChargeParams.builder()
            .patriciaCase(patriciaCase)
            .workCode(workCode.get())
            .userId(user.get())
            .timeRegComment(timeRegComment)
            .chargeComment(chargeComment)
            .actualHours(actualWorkedHoursPerCase)
            .chargeableHours(chargeableHoursPerCase)
            .recordalDate(activityStartTime.get())
            .build()
        );

    log.debug("Posted time after modification: {}",
        Base64.getEncoder().encodeToString(userPostedTime.toString().getBytes()));

    try {
      List<Tag> tagsMissingInPatricia = new ArrayList<>();

      List<Case> casesToPostTo = userPostedTime
          .getTags()
          .stream()

          .map(tag -> {
            Optional<Case> patriciaCase = patriciaDao.findCaseByCaseNumber(tag.getName());
            if (patriciaCase.isEmpty()) {
              tagsMissingInPatricia.add(tag);
            }
            return patriciaCase;
          })
          .filter(Optional::isPresent)
          .map(Optional::get)
          .collect(Collectors.toList());

      if (!tagsMissingInPatricia.isEmpty()) {
        log.warn("Couldn't find all tags in Patricia");
        tagsMissingInPatricia.forEach(tag -> {
          try {
            // the tag will be deleted, but the user still needs to manually repost and existing time rows need
            // to be fixed
            apiClient.tagDelete(new DeleteTagRequest().name(tag.getName()));
          } catch (IOException e) {
            log.error("Error deleting tag: {}", tag, e);
            // connect-api-server down: Throw general exception to retry
            throw new RuntimeException(e);
          }
        });
        throw new ConnectorException("Patricia case was not found for next tags: "
            + tagsMissingInPatricia.stream()
            .map(Tag::getName)
            .collect(Collectors.joining(", ")));
      }

      patriciaDao.asTransaction(() -> casesToPostTo.forEach(createTimeAndChargeRecord));
    } catch (ConnectorException e) {
      log.warn("Can't post time to the Patricia database: " + e.getMessage());
      return PostResult.PERMANENT_FAILURE()
          .withError(e)
          .withMessage(e.getMessage());
    } catch (RuntimeException e) {
      log.warn("Failed to save posted time in Patricia", e);
      return PostResult.TRANSIENT_FAILURE()
          .withError(e)
          .withMessage("There was an error posting time to the Patricia database");
    }
    return PostResult.SUCCESS();
  }

  private List<Tag> getRelevantTags(TimeGroup userPostedTime) {
    return userPostedTime.getTags().stream()
        .filter(tag -> {
          if (!createdByConnector(tag)) {
            log.warn("The Patricia connector is not configured to handle this tag: {}. No time will be posted for this tag.",
                tag.getName());
            return false;
          }
          return true;
        })
        .collect(Collectors.toList());
  }

  @VisibleForTesting
  void syncNewCases() {
    while (true) {
      final Optional<Long> storedLastSyncedCaseId = connectorStore.getLong(PATRICIA_LAST_SYNC_KEY);

      final List<Case> newCases = patriciaDao.findCasesOrderById(
          storedLastSyncedCaseId.orElse(0L),
          tagUpsertBatchSize()
      );

      if (newCases.isEmpty()) {
        log.info("No new cases found. Last case ID synced: {}",
            storedLastSyncedCaseId.map(String::valueOf).orElse("None"));
        return;
      }

      log.info("Detected {} new {}: {}",
          newCases.size(),
          newCases.size() > 1 ? "tags" : "tag",
          newCases.stream().map(Case::caseNumber).collect(Collectors.joining(", ")));

      upsertWiseTimeTags(newCases);

      final long lastSyncedCaseId = newCases.get(newCases.size() - 1).caseId();
      connectorStore.putLong(PATRICIA_LAST_SYNC_KEY, lastSyncedCaseId);
      log.info("Last synced case ID: {}", lastSyncedCaseId);
    }
  }

  @VisibleForTesting
  void refreshCases(final int batchSize) {
    final long lastPreviouslyRefreshedCaseId = connectorStore.getLong(PATRICIA_LAST_REFRESHED_KEY).orElse(0L);

    final List<Case> refreshCases = patriciaDao.findCasesOrderById(
        lastPreviouslyRefreshedCaseId,
        batchSize
    );

    if (refreshCases.isEmpty()) {
      // Start over the next time we are called
      connectorStore.putLong(PATRICIA_LAST_REFRESHED_KEY, 0L);
      return;
    }

    log.info("Refreshing {} {}: {}",
        refreshCases.size(),
        refreshCases.size() > 1 ? "tags" : "tag",
        refreshCases.stream().map(Case::caseNumber).collect(Collectors.joining(", ")));

    upsertWiseTimeTags(refreshCases);

    final long lastRefreshedCaseId = refreshCases.get(refreshCases.size() - 1).caseId();
    connectorStore.putLong(PATRICIA_LAST_REFRESHED_KEY, lastRefreshedCaseId);
    log.info("Last refreshed case ID: {}", lastRefreshedCaseId);
  }

  private void upsertWiseTimeTags(final List<Case> cases) {
    try {
      final List<UpsertTagRequest> upsertRequests = cases
          .stream()
          .map(item -> item.toUpsertTagRequest(tagUpsertPath()))
          .collect(Collectors.toList());

      apiClient.tagUpsertBatch(upsertRequests);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private void syncWorkCodes() {
    final List<String> hashes = new ArrayList<>();
    final int workCodesCount = iterateAllWorkCodes(workCodes -> hashes.add(hashFunction.hashWorkCodes(workCodes)));
    final String currentHash = hashFunction.hashStrings(hashes);

    final String prevSyncedHash = connectorStore.getString(PATRICIA_WORK_CODES_HASH_KEY).orElse(StringUtils.EMPTY);
    final long lastSync = connectorStore.getLong(PATRICIA_WORK_CODES_LAST_SYNC_KEY).orElse(0L);
    final boolean syncedMoreThanDayAgo = System.currentTimeMillis() - lastSync > TimeUnit.DAYS.toMillis(1);

    log.info("Sync Work Codes: {} work codes found with hash: '{}'. Previously synced at {} with hash: '{}'",
        workCodesCount, currentHash, lastSync, prevSyncedHash);

    if (!currentHash.equals(prevSyncedHash) || syncedMoreThanDayAgo) {
      final String syncSessionId = startSyncSession();
      iterateAllWorkCodes(workCodes -> sendActivityTypesToSync(mapToActivityTypes(workCodes), syncSessionId));
      completeSyncSession(syncSessionId);
      connectorStore.putString(PATRICIA_WORK_CODES_HASH_KEY, currentHash);
      connectorStore.putLong(PATRICIA_WORK_CODES_LAST_SYNC_KEY, System.currentTimeMillis());
      log.info("Work codes synced successfully finished with session '{}'", syncSessionId);
    } else {
      log.info("No need to sync work codes");
    }
  }

  private List<ActivityType> mapToActivityTypes(List<WorkCode> workCodes) {
    return workCodes.stream()
        .map(wc -> new ActivityType()
            .code(wc.workCodeId())
            .label(wc.workCodeText()))
        .collect(Collectors.toList());
  }

  private void sendActivityTypesToSync(List<ActivityType> activityTypes, String sessionId) {
    final SyncActivityTypesRequest request = new SyncActivityTypesRequest()
        .activityTypes(activityTypes)
        .syncSessionId(sessionId);
    try {
      apiClient.syncActivityTypes(request);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private String startSyncSession() {
    try {
      return apiClient.activityTypesStartSyncSession().getSyncSessionId();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private void completeSyncSession(String syncSessionId) {
    try {
      apiClient.activityTypesCompleteSyncSession(new SyncSession().syncSessionId(syncSessionId));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private int iterateAllWorkCodes(Consumer<List<WorkCode>> consumer) {
    int offset = 0;
    int counter = 0;
    while (true) {
      final List<WorkCode> workCodes = patriciaDao.findWorkCodes(offset, WORK_CODES_BATCH_SIZE);
      if (workCodes.size() > 0) {
        consumer.accept(workCodes);
        counter += workCodes.size();
      }
      if (workCodes.size() < WORK_CODES_BATCH_SIZE) {
        return counter;
      }
      offset += WORK_CODES_BATCH_SIZE;
    }
  }

  @Override
  public String getConnectorType() {
    return "wisetime-patricia-connector";
  }

  private Optional<String> getTimeGroupWorkCode(TimeGroup timeGroup) {
    final List<String> workCodes = timeGroup.getTimeRows().stream()
        .map(TimeRow::getActivityTypeCode)
        .distinct()
        .collect(Collectors.toList());
    if (workCodes.isEmpty()) {
      return Optional.empty();
    }
    if (workCodes.size() > 1) {
      log.error("All time logs within time group {} should have same activity type code, but got: {}",
          timeGroup.getGroupId(), workCodes);
      return Optional.empty();
    }
    return workCodes.stream()
        .filter(StringUtils::isNotEmpty)
        .findAny();
  }

  private int tagUpsertBatchSize() {
    return RuntimeConfig
        .getInt(PatriciaConnectorConfigKey.TAG_UPSERT_BATCH_SIZE)
        // A large batch mitigates query round trip latency
        .orElse(500);
  }

  @VisibleForTesting
  int tagRefreshBatchSize() {
    final long tagCount = patriciaDao.casesCount();
    final long batchFullFortnightlyRefresh = tagCount / (TimeUnit.DAYS.toMinutes(14) / tagSyncIntervalMinutes.get());

    if (batchFullFortnightlyRefresh > tagUpsertBatchSize()) {
      return tagUpsertBatchSize();
    }
    final int minimumBatchSize = 10;
    if (batchFullFortnightlyRefresh < minimumBatchSize) {
      return minimumBatchSize;
    }
    return (int) batchFullFortnightlyRefresh;
  }

  private String tagUpsertPath() {
    return RuntimeConfig
        .getString(PatriciaConnectorConfigKey.TAG_UPSERT_PATH)
        .orElse("/Patricia/");
  }

  private void executeCreateTimeAndChargeRecord(PatriciaDao.CreateTimeAndChargeParams params) {
    final String dbDate = patriciaDao.getDbDate();
    Function<BudgetLine, Long> addBudgetLine = patriciaDao::addBudgetLine;

    // Go through hierarchy of hourly rates step by step
    Optional<BigDecimal> hourlyRate = patriciaDao.findWorkCodeDefaultHourlyRate(params.workCode());
    Optional<String> currency = Optional.empty();
    if (hourlyRate.isEmpty()) {
      Optional<PatriciaDao.PriceListEntry> priceListEntry = patriciaDao
          .findHourlyRateFromPriceList(params.patriciaCase().caseId(), params.workCode(), params.userId(), roleTypeId);
      if (priceListEntry.isPresent()) {
        hourlyRate = Optional.of(priceListEntry.get().hourlyRate());
        currency = Optional.ofNullable(priceListEntry.get().currencyId());
        addBudgetLine = patriciaDao::addBudgetLineFromPriceList;
      }
    }

    if (hourlyRate.isEmpty()) {
      Optional<RateCurrency> rateCurrency = patriciaDao
          .findPatPersonHourlyRate(params.patriciaCase().caseId(), params.workCode(), params.userId());
      hourlyRate = rateCurrency.map(RateCurrency::hourlyRate);
      currency = rateCurrency.map(RateCurrency::currencyId);
    }

    if (hourlyRate.isEmpty()) {
      hourlyRate = patriciaDao.findPersonDefaultHourlyRate(params.userId());
    }

    if (currency.isEmpty()) {
      currency = Optional.of(getCurrency(params));
    }

    if (hourlyRate.isEmpty()) {
      throw new ConnectorException("No hourly rate is found for " + params.userId());
    }

    final List<Discount> discounts = patriciaDao.findDiscounts(
        params.workCode(), roleTypeId, params.patriciaCase().caseId()
    );
    final List<Discount> applicableDiscounts = ChargeCalculator.getMostApplicableDiscounts(discounts, params.patriciaCase());

    BigDecimal chargeWithoutDiscount = params.chargeableHours().multiply(hourlyRate.get());
    BigDecimal chargeWithDiscount = ChargeCalculator.calculateTotalCharge(
        applicableDiscounts, params.chargeableHours(), hourlyRate.get()
    );

    final int budgetLineSequenceNumber = patriciaDao.findNextBudgetLineSeqNum(params.patriciaCase().caseId());

    final String activityDate = ZonedDateTime.ofInstant(params.recordalDate(), ZoneOffset.UTC)
        .withZoneSameInstant(getTimeZoneId())
        .format(DATE_TIME_FORMATTER);

    BudgetLine budgetLine = BudgetLine.builder()
        .budgetLineSequenceNumber(budgetLineSequenceNumber)
        .caseId(params.patriciaCase().caseId())
        .workCodeId(params.workCode())
        .userId(params.userId())
        .submissionDate(dbDate)
        .currency(currency.get())
        .hourlyRate(hourlyRate.get())
        .actualWorkTotalHours(params.actualHours())
        .chargeableWorkTotalHours(params.chargeableHours())
        .chargeableAmount(chargeWithDiscount)
        .actualWorkTotalAmount(chargeWithoutDiscount)
        .discountAmount(ChargeCalculator.calculateDiscountAmount(chargeWithoutDiscount, chargeWithDiscount))
        .discountPercentage(ChargeCalculator.calculateDiscountPercentage(chargeWithoutDiscount, chargeWithDiscount))
        .effectiveHourlyRate(ChargeCalculator.calculateHourlyRate(chargeWithDiscount, params.chargeableHours()))
        .comment(params.chargeComment())
        .activityDate(activityDate)
        .chargeTypeId(RuntimeConfig.getInt(PatriciaConnectorConfigKey.WT_CHARGE_TYPE_ID).orElse(null))
        .build();

    TimeRegistration timeRegistration = TimeRegistration.builder()
        .budgetLineSequenceNumber(budgetLineSequenceNumber)
        .caseId(params.patriciaCase().caseId())
        .workCodeId(params.workCode())
        .userId(params.userId())
        .submissionDate(dbDate)
        .activityDate(activityDate)
        .actualHours(params.actualHours())
        .chargeableHours(params.chargeableHours())
        .comment(params.timeRegComment())
        .build();

    long numberOfAffectedBudgedHeaders = patriciaDao.updateBudgetHeader(params.patriciaCase().caseId(), dbDate);
    log.debug("Inserted or updated {} budget header entries for Patricia issue {} on behalf of {}",
        numberOfAffectedBudgedHeaders, params.patriciaCase().caseNumber(), params.userId());
    long numberOfBudgetLinesInserted = addBudgetLine.apply(budgetLine);
    log.debug("Inserted {} budget line entries for Patricia issue {} on behalf of {}",
        numberOfBudgetLinesInserted, params.patriciaCase().caseNumber(), params.userId());
    long numberOfTimeRegistrationsInserted = patriciaDao.addTimeRegistration(timeRegistration);
    log.debug("Inserted {} time registration entries for Patricia issue {} on behalf of {}",
        numberOfTimeRegistrationsInserted, params.patriciaCase().caseNumber(), params.userId());

    log.info("Posted time to Patricia issue {} on behalf of {}", params.patriciaCase().caseNumber(), params.userId());
  }

  private String getCurrency(PatriciaDao.CreateTimeAndChargeParams params) {
    Optional<String> fallbackCurrency = RuntimeConfig.getString(PatriciaConnectorConfigKey.FALLBACK_CURRENCY);
    if (RuntimeConfig.getBoolean(PatriciaConnectorConfigKey.USE_SYSDEFAULT_CURRENCY_FOR_POSTING).orElse(false)) {
      return Stream.of(patriciaDao.getSystemDefaultCurrency(), fallbackCurrency)
          .filter(Optional::isPresent)
          .map(Optional::get)
          .findFirst()
          .orElseThrow(() -> new ConnectorException("Could not find the system default currency."));
    }
    return Stream.of(patriciaDao.findCurrency(params.patriciaCase().caseId(), roleTypeId), fallbackCurrency)
        .filter(Optional::isPresent)
        .map(Optional::get)
        .findFirst()
        .orElseThrow(() -> new ConnectorException(
            "Could not find currency for the case " + params.patriciaCase().caseNumber()
                + ". Please make sure an account address is configured for this case in the 'Parties' tab.")
        );
  }

  private TemplateFormatter createTemplateFormatter(String getTemplatePath) {
    return new TemplateFormatter(TemplateFormatterConfig.builder()
        .withTemplatePath(getTemplatePath)
        .withDisplayZone(DisplayZone.USER_LOCAL)
        .build());
  }

  private ZoneId getTimeZoneId() {
    return ZoneId.of(RuntimeConfig.getString(PatriciaConnectorConfigKey.TIMEZONE).orElse("UTC"));
  }

  private Optional<String> getPatriciaLoginId(User user) {

    if (StringUtils.isNotBlank(user.getExternalId())) {
      if (patriciaDao.loginIdExists(user.getExternalId())) {
        // return External ID if it's the user's Login ID/Username in Patricia
        return Optional.of(user.getExternalId());

      } else if (user.getExternalId().split("@").length == 2) {
        // if External ID is not the Login ID but it looks like an email, try to find a user with that email
        return patriciaDao.findLoginIdByEmail(user.getExternalId());
      }

    } else {
      // If user has no defined External ID, use his/her email to check for a Patricia user
      return patriciaDao.findLoginIdByEmail(user.getEmail());
    }

    return Optional.empty();
  }

  @Override
  public void shutdown() {
    patriciaDao.shutdown();
  }

  private boolean createdByConnector(Tag tag) {
    return tag.getPath().equals(tagUpsertPath())
            || tag.getPath().equals(StringUtils.strip(tagUpsertPath(), "/"));
  }
}

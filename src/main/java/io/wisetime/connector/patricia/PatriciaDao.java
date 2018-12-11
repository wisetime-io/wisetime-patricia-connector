/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.patricia;

import com.google.common.collect.Lists;
import com.google.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.codejargon.fluentjdbc.api.FluentJdbc;
import org.codejargon.fluentjdbc.api.FluentJdbcBuilder;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.annotation.Nullable;
import javax.sql.DataSource;

import io.wisetime.generated.connect.Tag;
import io.wisetime.generated.connect.UpsertTagRequest;

/**
 * Simple, unsophisticated access to the Patricia database.
 *
 * @author vadym
 */
public class PatriciaDao {

  private final Logger log = LoggerFactory.getLogger(PatriciaDao.class);
  static final int PURE_DISCOUNT = 1;
  static final int MARK_UP_DISCOUNT = 2;
  private final FluentJdbc fluentJdbc;

  @Inject
  PatriciaDao(DataSource dataSource) {
    fluentJdbc = new FluentJdbcBuilder().connectionProvider(dataSource).build();
  }

  public void asTransaction(final Runnable runnable) {
    fluentJdbc.query().transaction().inNoResult(runnable);
  }

  boolean isHealthy() {
    return getDbDate().isPresent();
  }

  List<Case> findCasesOrderById(final long startIdExclusive, final int maxResults) {
    // TODO: Implement
    return Collections.emptyList();
  }

  Optional<String> findLoginByEmail(final String email) {
    // TODO: Implement
    return Optional.empty();
  }

  public Map<Integer, Tag> findCaseIds(final List<Tag> tags) {
    // TODO: Implement
    return Collections.emptyMap();
  }

  public void updateBudgetHeader(final int caseId) {
    // TODO: Implement
  }

  public Optional<String> findCurrency(final int caseId) {
    return Optional.empty();
  }

  public Optional<BigDecimal> findUserHourlyRate(final String workCodeId, final String loginId) {
    // TODO: Implement
    return Optional.empty();
  }

  public List<Discount> findDiscountRecords(final String workCodeId, final int caseId) {
    // TODO: Implement
    return Collections.emptyList();
  }

  public Optional<Case> findPatCaseData(final int caseId) {
    // TODO: Implement
    return Optional.empty();
  }

  public void addTimeRegistration(final PostTimeData commonParams,
                                  final BillingData billingData,
                                  final String comment) {
    // TODO: Implement
  }

  public void addBudgetLine(final PostTimeData commonParams, final BillingData billingData, final String comment) {
    // TODO: Implement
  }

  public Optional<String> getDbDate() {
    // TODO: Implement
    return Optional.empty();
  }

  public PatriciaDao.BillingData calculateBilling(final PostTimeData commonParams,
                                                  final int chargeableTimeInSecs,
                                                  final int actualWorkedTimeInSecs) {
    final String currency = findCurrency(commonParams.caseId())
        .orElseThrow(() -> new RuntimeException(
            "Could not find external system currency for case " + commonParams.caseName())
        );
    final BigDecimal hourlyRate = findUserHourlyRate(commonParams.workCodeId(), commonParams.loginId())
        .orElseThrow(() -> new RuntimeException(
            "Could not find external system case hourly rate.")
        );

    final Optional<PatriciaDao.Discount> discount = findMostApplicableDiscount(
        commonParams.workCodeId(), commonParams.caseId()
    );

    // compute worked hours based on user experience factor
    final int actualWorkSecsWithExpFactor =
        getDurationByPercentage(actualWorkedTimeInSecs, commonParams.experienceWeightingPercent());
    final int chargeableWorkSecsWithExpFactor =
        getDurationByPercentage(chargeableTimeInSecs, commonParams.experienceWeightingPercent());

    return ImmutableBillingData.builder()
        .currency(currency)
        .hourlyRate(hourlyRate)
        .actualWork(computeWorkedHours(actualWorkedTimeInSecs, hourlyRate, discount))
        .chargeableWork(computeWorkedHours(chargeableTimeInSecs, hourlyRate, discount))
        .actualWorkWithExperienceFactor(computeWorkedHours(actualWorkSecsWithExpFactor, hourlyRate, discount))
        .chargeableWorkWithExperienceFactor(computeWorkedHours(chargeableWorkSecsWithExpFactor, hourlyRate, discount))
        .build();
  }

  Optional<PatriciaDao.Discount> findMostApplicableDiscount(final String workCodeId, final int caseId) {

    final List<PatriciaDao.Discount> discounts = Lists.newArrayList(
        findDiscountRecords(workCodeId, caseId) // convert to ArrayList so we can sort
    );

    // do not proceed when no discount is applicable
    if (discounts.isEmpty()) {
      return Optional.empty();
    }

    // From the applicable discount, get discount that matches most to the case (tag name)
    final Optional<PatriciaDao.Discount> matchingDiscount = findDiscountMatchingPatriciaCase(discounts, caseId);

    if (matchingDiscount.isPresent()) {
      // return the discount matching the most to the case
      return matchingDiscount;
    } else {
      // return the general discount with highest priority
      sortDiscountsByHighestPriority(discounts);
      return Optional.of(discounts.get(0));
    }
  }

  Optional<PatriciaDao.Discount> findDiscountMatchingPatriciaCase(final List<PatriciaDao.Discount> discounts,
                                                                  final int caseId) {
    final Optional<PatriciaDao.Case> patCase = findPatCaseData(caseId);

    if (patCase.isPresent()) {
      // find discount that matches the Patricia case
      final List<PatriciaDao.Discount> matchingDiscounts = discounts
          .stream()
          .filter(discount ->
              discount.caseTypeId() == null || Objects.equals(discount.caseTypeId(), patCase.get().caseTypeId()))
          .filter(discount ->
              discount.stateId() == null || Objects.equals(discount.stateId(), patCase.get().stateId()))
          .filter(discount ->
              discount.applicationTypeId() == null || Objects.equals(discount.applicationTypeId(), patCase.get().appId()))
          .collect(Collectors.toList());

      if (!matchingDiscounts.isEmpty()) {
        sortDiscountsByHighestPriority(matchingDiscounts);

        // throws RuntimeException if there is indistinct account policy.
        assertDiscountHasNoSamePriority(matchingDiscounts.get(0), matchingDiscounts);

        // return the matching discount with highest priority
        return Optional.of(matchingDiscounts.get(0));
      }
    }

    return Optional.empty();
  }

  void sortDiscountsByHighestPriority(final List<PatriciaDao.Discount> discounts) {
    if (discounts.size() > 1) {
      discounts.sort(Collections.reverseOrder(Comparator.comparingInt(PatriciaDao.Discount::priority)));
    }
  }

  void assertDiscountHasNoSamePriority(final PatriciaDao.Discount discountToCheck,
                                       final List<PatriciaDao.Discount> otherDiscounts) {
    final boolean hasSamePriority = otherDiscounts
        .stream()
        .anyMatch(
            otherDiscount ->
                !otherDiscount.equals(discountToCheck) // ensure we are not comparing to same discount
                    && otherDiscount.priority() == discountToCheck.priority() // check for same priority
        );

    if (hasSamePriority) {
      throw new RuntimeException("Indistinct discount policy for case/person combination detected. Please resolve.");
    }
  }

  int getDurationByPercentage(final int originalDuration, final float percentage) {
    return (int) (originalDuration * percentage / 100);
  }

  PatriciaDao.WorkedHoursComputation computeWorkedHours(final int workedTimeInSecs,
                                                        final BigDecimal hourlyRate,
                                                        final Optional<PatriciaDao.Discount> discount) {
    final BigDecimal workedHours = calculateDurationToHours(workedTimeInSecs);
    BigDecimal totalAmount = BigDecimal.ZERO;
    BigDecimal discountAmount = BigDecimal.ZERO;
    BigDecimal discountPercentage = BigDecimal.ZERO;

    if (discount.isPresent()) {
      PatriciaDao.Discount discountToApply = discount.get();
      if (discountToApply.discountType() == PURE_DISCOUNT) {
        // 1 means pure discount, system should deduct discount to the billing amount
        totalAmount = calculateDiscountedBillingAmount(discountToApply, workedHours, hourlyRate);
      } else if (discountToApply.discountType() == MARK_UP_DISCOUNT) {
        // 2 means mark up discount, system should ADD the discount to the billing amount
        totalAmount = calculateMarkedUpBillingAmount(discountToApply, workedHours, hourlyRate);
      } else {
        log.warn("Unexpected discount type {}. Treat as no discount", discountToApply.discountType());
      }
    }

    BigDecimal billingAmountWithoutDiscount = workedHours.multiply(hourlyRate);
    if (totalAmount.compareTo(BigDecimal.ZERO) == 0) {
      // if final billing amount is not set, it should be equal to the billingAmountWithoutDiscount
      totalAmount = billingAmountWithoutDiscount;
    } else {
      // compute discount amount and percentage if billing amount has discount/markup
      discountAmount = totalAmount.subtract(billingAmountWithoutDiscount);
      discountPercentage = discountAmount
          .divide(billingAmountWithoutDiscount, 5, RoundingMode.HALF_UP)
          .multiply(BigDecimal.valueOf(100));

    }

    return ImmutableWorkedHoursComputation.builder()
        .totalHours(workedHours)
        .totalAmount(totalAmount)
        .discountAmount(discountAmount)
        .discountPercentage(discountPercentage)
        .discountedHourlyRate(totalAmount.divide(workedHours, 2, RoundingMode.HALF_UP))
        .build();
  }

  BigDecimal calculateDurationToHours(final int durationSecs) {
    return BigDecimal
        .valueOf(durationSecs)
        .divide(BigDecimal.valueOf(3600), 2, BigDecimal.ROUND_HALF_UP);
  }

  BigDecimal calculateDiscountedBillingAmount(final PatriciaDao.Discount discountToApply,
                                              final BigDecimal durationInHours,
                                              final BigDecimal hourlyRate) {
    if (discountToApply.discountPercent().compareTo(BigDecimal.ZERO) == 0) {
      return durationInHours.multiply(hourlyRate) // amount without discount
          .subtract(discountToApply.amount())
          .setScale(2, RoundingMode.HALF_UP); // discounted amount
    } else {
      final BigDecimal hourlyRateDiscount = hourlyRate.multiply(discountToApply.discountPercent())
          .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
      final BigDecimal discountedRate = hourlyRate.subtract(hourlyRateDiscount);
      return durationInHours.multiply(discountedRate)
          .setScale(2, RoundingMode.HALF_UP);
    }
  }

  BigDecimal calculateMarkedUpBillingAmount(final PatriciaDao.Discount discountToApply,
                                            final BigDecimal durationInHours,
                                            final BigDecimal hourlyRate) {
    if (discountToApply.discountPercent().compareTo(BigDecimal.ZERO) == 0) {
      return durationInHours.multiply(hourlyRate) // amount without markup
          .add(discountToApply.amount())
          .setScale(2, RoundingMode.HALF_UP); // marked up amount
    } else {
      final BigDecimal ratePerSecDiscount = hourlyRate.multiply(discountToApply.discountPercent())
          .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
      final BigDecimal markedUpRateRate = hourlyRate.add(ratePerSecDiscount);
      return durationInHours.multiply(markedUpRateRate)
          .setScale(2, RoundingMode.HALF_UP);
    }
  }

  /**
   * This maps some fields from pat_case and vw_case_number tables in Patricia DB.
   * It holds relevant data for a case, which are needed for creating tag and calculating discount priority.
   */
  @Value.Immutable
  public interface Case {

    long getId();

    String caseNumber();

    String caseCatchWord();

    Integer caseTypeId();

    String stateId();

    Integer appId();

    default UpsertTagRequest toUpsertTagRequest(final String path) {
      final UpsertTagRequest upsertTagRequest = new UpsertTagRequest();
      upsertTagRequest.path(path);
      upsertTagRequest.name(caseNumber());
      upsertTagRequest.setDescription(StringUtils.trimToEmpty(caseCatchWord()));
      return upsertTagRequest;
    }
  }

  /**
   * This encapsulates Patricia's time record discount information which is used by agent to
   * calculate discount percent and amount for time record.
   */
  @Value.Immutable
  public interface Discount {

    @Nullable
    Integer caseTypeId();

    @Nullable
    String stateId();

    @Nullable
    Integer applicationTypeId();

    @Nullable
    String workCodeId();

    @Nullable
    String workCodeType();

    @Value.Default
    default int priority() {
      return 0;
    }

    @Value.Default
    default int discountId() {
      return 0;
    }

    @Value.Default
    default int discountType() {
      return 0;
    }

    @Value.Default
    default BigDecimal amount() {
      return BigDecimal.ZERO;
    }

    @Value.Default
    default BigDecimal discountPercent() {
      return BigDecimal.ZERO;
    }
  }

  @Value.Immutable
  public interface PostTimeData {

    Integer caseId();

    String caseName();

    String workCodeId();

    String recordalDate();

    String loginId();

    float experienceWeightingPercent();
  }

  @Value.Immutable
  public interface WorkedHoursComputation {

    BigDecimal totalHours();

    BigDecimal totalAmount();

    BigDecimal discountAmount();

    BigDecimal discountPercentage();

    BigDecimal discountedHourlyRate();
  }

  @Value.Immutable
  public interface BillingData {

    String currency();

    BigDecimal hourlyRate();

    WorkedHoursComputation actualWork();

    WorkedHoursComputation chargeableWork();

    WorkedHoursComputation actualWorkWithExperienceFactor();

    WorkedHoursComputation chargeableWorkWithExperienceFactor();
  }
}

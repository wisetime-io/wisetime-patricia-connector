/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.patricia.posting_time;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.wisetime.connector.patricia.PatriciaDao;

/**
 * @author vadym
 */
@Singleton
public class BillingService {

  private static final Logger log = LoggerFactory.getLogger(BillingService.class);

  @VisibleForTesting
  static final int PURE_DISCOUNT = 1;
  @VisibleForTesting
  static final int MARK_UP_DISCOUNT = 2;

  private final PatriciaDao patriciaDao;

  @Inject
  public BillingService(PatriciaDao patriciaDao) {
    this.patriciaDao = patriciaDao;
  }

  public BillingData calculateBilling(final PostTimeCommonParams commonParams,
                                      final int chargeableTimeInSecs,
                                      final int actualWorkedTimeInSecs) {
    final String currency = patriciaDao.findCurrency(commonParams.caseId())
        .orElseThrow(() -> new RuntimeException(
            "Could not find external system currency for case " + commonParams.caseName())
        );
    final BigDecimal hourlyRate = patriciaDao.findUserHourlyRate(commonParams.workCodeId(), commonParams.loginId())
        .orElseThrow(() -> new RuntimeException(
            "Could not find external system case hourly rate.")
        );

    final Optional<PatriciaDiscountRecord> discount = findMostApplicableDiscount(
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

  @VisibleForTesting
  Optional<PatriciaDiscountRecord> findMostApplicableDiscount(final String workCodeId, final int caseId) {

    final List<PatriciaDiscountRecord> discounts = Lists.newArrayList(
        patriciaDao.findDiscountRecords(workCodeId, caseId) // convert to ArrayList so we can sort
    );

    // do not proceed when no discount is applicable
    if (discounts.isEmpty()) {
      return Optional.empty();
    }

    // From the applicable discount, get discount that matches most to the case (tag name)
    final Optional<PatriciaDiscountRecord> matchingDiscount = findDiscountMatchingPatriciaCase(discounts, caseId);

    if (matchingDiscount.isPresent()) {
      // return the discount matching the most to the case
      return matchingDiscount;
    } else {
      // return the general discount with highest priority
      sortDiscountsByHighestPriority(discounts);
      return Optional.of(discounts.get(0));
    }
  }

  @VisibleForTesting
  Optional<PatriciaDiscountRecord> findDiscountMatchingPatriciaCase(final List<PatriciaDiscountRecord> discounts,
                                                                    final int caseId) {
    final Optional<PatriciaCaseRecord> patCase = patriciaDao.findPatCaseData(caseId);

    if (patCase.isPresent()) {
      // find discount that matches the Patricia case
      final List<PatriciaDiscountRecord> matchingDiscounts = discounts
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

  @VisibleForTesting
  void sortDiscountsByHighestPriority(final List<PatriciaDiscountRecord> discounts) {
    if (discounts.size() > 1) {
      discounts.sort(Collections.reverseOrder(Comparator.comparingInt(PatriciaDiscountRecord::priority)));
    }
  }

  @VisibleForTesting
  void assertDiscountHasNoSamePriority(final PatriciaDiscountRecord discountToCheck,
                                       final List<PatriciaDiscountRecord> otherDiscounts) {
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

  @VisibleForTesting
  int getDurationByPercentage(final int originalDuration, final float percentage) {
    return (int) (originalDuration * percentage / 100);
  }

  @VisibleForTesting
  WorkedHoursComputation computeWorkedHours(final int workedTimeInSecs,
                                            final BigDecimal hourlyRate,
                                            final Optional<PatriciaDiscountRecord> discount) {
    final BigDecimal workedHours = calculateDurationToHours(workedTimeInSecs);
    BigDecimal totalAmount = BigDecimal.ZERO;
    BigDecimal discountAmount = BigDecimal.ZERO;
    BigDecimal discountPercentage = BigDecimal.ZERO;

    if (discount.isPresent()) {
      PatriciaDiscountRecord discountToApply = discount.get();
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

  @VisibleForTesting
  BigDecimal calculateDurationToHours(final int durationSecs) {
    return BigDecimal
        .valueOf(durationSecs)
        .divide(BigDecimal.valueOf(3600), 2, BigDecimal.ROUND_HALF_UP);
  }

  @VisibleForTesting
  BigDecimal calculateDiscountedBillingAmount(final PatriciaDiscountRecord discountToApply,
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

  @VisibleForTesting
  BigDecimal calculateMarkedUpBillingAmount(final PatriciaDiscountRecord discountToApply,
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
}

/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All rights reserved.
 */

package io.wisetime.connector.patricia.util;

import com.google.common.collect.Lists;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import static io.wisetime.connector.patricia.PatriciaDao.Case;
import static io.wisetime.connector.patricia.PatriciaDao.Discount;

/**
 * @author alvin.llobrera@practiceinsight.io
 */
public class ChargeCalculator {

  public static final int DISCOUNT_TYPE_PURE = 1;
  public static final int DISCOUNT_TYPE_MARKUP = 2;

  public static Optional<Discount> getMostApplicableDiscount(List<Discount> applicableDiscounts, Case patriciaCase) {
    // do not proceed when no discount is applicable
    if (applicableDiscounts.isEmpty()) {
      return Optional.empty();
    }

    // From the applicable discount, get discount that matches most to the case (tag name)
    final Optional<Discount> matchingDiscount = findDiscountMatchingPatriciaCase(
        applicableDiscounts, patriciaCase
    );

    if (matchingDiscount.isPresent()) {
      // return the discount matching the most to the case
      return matchingDiscount;
    } else {
      // return the general discount with highest priority
      List<Discount> sortedDiscounts = sortDiscountsByHighestPriority(applicableDiscounts);
      return Optional.of(sortedDiscounts.get(0));
    }
  }

  public static BigDecimal calculateTotalCharge(Optional<Discount> discount,
                                                BigDecimal durationInHours,
                                                BigDecimal hourlyRate) {
    if (discount.isPresent()) {
      if (discount.get().discountType() == DISCOUNT_TYPE_PURE) {
        // 1 means pure discount, system should deduct discount to the billing amount
        return calculateDiscountedBillingAmount(discount.get(), durationInHours, hourlyRate);
      } else if (discount.get().discountType() == DISCOUNT_TYPE_MARKUP) {
        // 2 means mark up discount, system should ADD the discount to the billing amount
        return calculateMarkedUpBillingAmount(discount.get(), durationInHours, hourlyRate);
      }
    }

    // Compute charge without any discount/markup
    return durationInHours.multiply(hourlyRate);
  }

  public static BigDecimal calculateDiscountAmount(BigDecimal amountWithoutDiscount, BigDecimal amountWithDiscount) {
    return amountWithDiscount.subtract(amountWithoutDiscount);
  }

  public static BigDecimal calculateDiscountPercentage(BigDecimal amountWithoutDiscount, BigDecimal amountWithDiscount) {
    return amountWithDiscount
        .subtract(amountWithoutDiscount)
        .divide(amountWithoutDiscount, 5, RoundingMode.HALF_UP)
        .multiply(BigDecimal.valueOf(100));
  }

  public static BigDecimal calculateHourlyRate(BigDecimal totalCharge, BigDecimal durationInHours) {
    return totalCharge.divide(durationInHours, 2, RoundingMode.HALF_UP);
  }

  public static BigDecimal calculateDurationToHours(double durationSecs) {
    return BigDecimal
        .valueOf(durationSecs)
        .divide(BigDecimal.valueOf(3600), 2, BigDecimal.ROUND_HALF_UP);
  }

  private static Optional<Discount> findDiscountMatchingPatriciaCase(List<Discount> discounts, Case patriciaCase) {
    // find discount that matches the Patricia case
    final List<Discount> matchingDiscounts = discounts
        .stream()
        .filter(discount ->
            discount.caseTypeId() == null || Objects.equals(discount.caseTypeId(), patriciaCase.caseTypeId()))
        .filter(discount ->
            discount.stateId() == null || Objects.equals(discount.stateId(), patriciaCase.stateId()))
        .filter(discount ->
            discount.applicationTypeId() == null || Objects.equals(discount.applicationTypeId(), patriciaCase.appId()))
        .collect(Collectors.toList());

    if (!matchingDiscounts.isEmpty()) {
      sortDiscountsByHighestPriority(matchingDiscounts);

      // throws RuntimeException if there is indistinct account policy.
      assertDiscountHasNoSamePriority(matchingDiscounts.get(0), matchingDiscounts);

      // return the matching discount with highest priority
      return Optional.of(matchingDiscounts.get(0));
    }

    return Optional.empty();
  }

  private static List<Discount> sortDiscountsByHighestPriority(List<Discount> discounts) {
    if (discounts.size() > 1) {
      List<Discount> sortableDiscounts = Lists.newArrayList(discounts);
      sortableDiscounts.sort(Collections.reverseOrder(Comparator.comparingInt(Discount::priority)));
      return sortableDiscounts;
    } else {
      return discounts;
    }
  }

  private static void assertDiscountHasNoSamePriority(Discount discountToCheck, List<Discount> otherDiscounts) {
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

  private static BigDecimal calculateDiscountedBillingAmount(Discount discountToApply,
                                                             BigDecimal durationInHours,
                                                             BigDecimal hourlyRate) {
    if (discountToApply.discountPercent().compareTo(BigDecimal.ZERO) == 0) {
      return durationInHours
          .multiply(hourlyRate) // amount without discount
          .subtract(discountToApply.amount()); // discounted amount

    } else {
      final BigDecimal hourlyRateDiscount = hourlyRate
          .multiply(discountToApply.discountPercent())
          .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
      final BigDecimal discountedRate = hourlyRate.subtract(hourlyRateDiscount);
      return durationInHours.multiply(discountedRate);
    }
  }

  private static BigDecimal calculateMarkedUpBillingAmount(Discount discountToApply,
                                                    BigDecimal durationInHours,
                                                    BigDecimal hourlyRate) {
    if (discountToApply.discountPercent().compareTo(BigDecimal.ZERO) == 0) {
      return durationInHours
          .multiply(hourlyRate) // amount without markup
          .add(discountToApply.amount()); // marked up amount

    } else {
      final BigDecimal ratePerSecDiscount = hourlyRate
          .multiply(discountToApply.discountPercent())
          .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
      final BigDecimal markedUpRateRate = hourlyRate.add(ratePerSecDiscount);
      return durationInHours.multiply(markedUpRateRate);
    }
  }
}

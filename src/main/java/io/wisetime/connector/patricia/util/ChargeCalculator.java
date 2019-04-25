/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All rights reserved.
 */

package io.wisetime.connector.patricia.util;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import net.objecthunter.exp4j.ExpressionBuilder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import io.wisetime.connector.utils.DurationCalculator;
import io.wisetime.connector.utils.DurationSource;
import io.wisetime.generated.connect.TimeGroup;

import static io.wisetime.connector.patricia.PatriciaDao.Case;
import static io.wisetime.connector.patricia.PatriciaDao.Discount;

/**
 * @author alvin.llobrera@practiceinsight.io
 */
public class ChargeCalculator {

  private static final Logger log = LoggerFactory.getLogger(ChargeCalculator.class);

  public static final int DISCOUNT_TYPE_PURE = 1;
  public static final int DISCOUNT_TYPE_MARKUP = 2;

  public static List<Discount> getMostApplicableDiscounts(List<Discount> applicableDiscounts, Case patriciaCase) {
    // do not proceed when no discount is applicable
    if (applicableDiscounts.isEmpty()) {
      return ImmutableList.of();
    }

    // From the applicable discount, get discount that matches most to the case (tag name)
    final List<Discount> matchingDiscounts = findDiscountMatchingPatriciaCase(
        applicableDiscounts, patriciaCase
    );

    if (!matchingDiscounts.isEmpty()) {
      // return the discount matching the most to the case
      return matchingDiscounts;
    } else {
      // return the general discount with highest priority
      return sortDiscountsByHighestPriority(applicableDiscounts);
    }
  }

  public static BigDecimal calculateTotalCharge(List<Discount> discounts,
                                                BigDecimal durationInHours,
                                                BigDecimal hourlyRate) {
    BigDecimal amount = durationInHours.multiply(hourlyRate);
    List<Discount> discountsSortedByAmount = new ArrayList<>(discounts);
    discountsSortedByAmount.sort(Collections.reverseOrder(Comparator.comparing(Discount::amount)));
    // Filter out all discount where the undiscounted amount is strictly smaller than the discount's amount threshold
    // and return the largest one, if one exists
    Optional<Discount> discount = discountsSortedByAmount.stream()
        .filter(dis -> amount.compareTo(dis.amount()) >= 0)
        .findFirst();
    if (discount.isPresent()) {
      if (discount.get().discountType() == DISCOUNT_TYPE_PURE) {
        // 1 means pure discount, system should deduct discount from the billing amount
        return calculateDiscountedBillingAmount(discount.get(), amount);
      } else if (discount.get().discountType() == DISCOUNT_TYPE_MARKUP) {
        // 2 means mark up discount, system should ADD the discount to the billing amount
        return calculateMarkedUpBillingAmount(discount.get(), amount);
      } else {
        throw new RuntimeException("Unknown discount type " + discount.get().discountType());
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

  public static BigDecimal calculateActualWorkedHoursNoExpRatingPerCase(TimeGroup userPostedTime) {
    return toHours(
        DurationCalculator
            .of(userPostedTime)
            .useDurationFrom(DurationSource.SUM_TIME_ROWS)
            .disregardExperienceWeighting()
            .calculate()
            .getPerTagDuration()
    );
  }

  public static BigDecimal calculateActualWorkedHoursWithExpRatingPerCase(TimeGroup userPostedTime) {
    return toHours(
        DurationCalculator
            .of(userPostedTime)
            .useDurationFrom(DurationSource.SUM_TIME_ROWS)
            .calculate()
            .getPerTagDuration()
    );
  }

  public static BigDecimal calculateChargeableWorkedHoursNoExpRatingPerCase(TimeGroup userPostedTime) {
    return toHours(
        DurationCalculator
            .of(userPostedTime)
            .useDurationFrom(DurationSource.TIME_GROUP)
            .disregardExperienceWeighting()
            .calculate()
            .getPerTagDuration()
    );
  }

  public static BigDecimal calculateChargeableWorkedHoursWithExpRatingPerCase(TimeGroup userPostedTime) {
    return toHours(
        DurationCalculator
            .of(userPostedTime)
            .useDurationFrom(DurationSource.TIME_GROUP)
            .calculate()
            .getPerTagDuration()
    );
  }

  private static BigDecimal toHours(long durationSecs) {
    return BigDecimal
        .valueOf(durationSecs)
        .divide(BigDecimal.valueOf(3600), 2, BigDecimal.ROUND_HALF_UP);
  }

  private static List<Discount> findDiscountMatchingPatriciaCase(List<Discount> discounts, Case patriciaCase) {
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

      // return the matching discount with highest priority
      return sortDiscountsByHighestPriority(matchingDiscounts);
    }

    return ImmutableList.of();
  }

  private static List<Discount> sortDiscountsByHighestPriority(List<Discount> discounts) {
    if (discounts.size() > 1) {
      List<Discount> sortableDiscounts = Lists.newArrayList(discounts);
      sortableDiscounts.sort(Collections.reverseOrder(Comparator.comparingInt(Discount::priority)));
      final int highestPriority = sortableDiscounts.get(0).priority();
      return sortableDiscounts.stream()
          .filter(discount -> discount.priority() == highestPriority)
          .collect(Collectors.toList());
    } else {
      return discounts;
    }
  }

  private static BigDecimal calculateDiscountedBillingAmount(Discount discountToApply,
                                                             BigDecimal originalAmount) {
    double result = evaluateDiscountFormula(discountToApply, originalAmount);

    return originalAmount.subtract(new BigDecimal(result));
  }

  private static BigDecimal calculateMarkedUpBillingAmount(Discount discountToApply,
                                                           BigDecimal originalAmount) {
    double result = evaluateDiscountFormula(discountToApply, originalAmount);

    return originalAmount.add(new BigDecimal(result));
  }

  private static double evaluateDiscountFormula(Discount discountToApply, BigDecimal originalAmount) {
    return new ExpressionBuilder(discountToApply.priceChangeFormula().replace('@', 'x'))
        .variable("x")
        .build()
        .setVariable("x", originalAmount.doubleValue())
        .evaluate();
  }
}

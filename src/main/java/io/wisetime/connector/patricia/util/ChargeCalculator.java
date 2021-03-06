/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All rights reserved.
 */

package io.wisetime.connector.patricia.util;

import static io.wisetime.connector.patricia.PatriciaDao.Case;
import static io.wisetime.connector.patricia.PatriciaDao.Discount;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import io.wisetime.connector.utils.DurationCalculator;
import io.wisetime.connector.utils.DurationSource;
import io.wisetime.generated.connect.TimeGroup;
import io.wisetime.generated.connect.TimeRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import net.objecthunter.exp4j.ExpressionBuilder;

/**
 * @author alvin.llobrera@practiceinsight.io
 */
public class ChargeCalculator {

  public static final int DISCOUNT_TYPE_PURE = 1;
  public static final int DISCOUNT_TYPE_MARKUP = 2;

  public static List<Discount> getMostApplicableDiscounts(List<Discount> applicableDiscounts, Case patriciaCase) {
    // do not proceed when no discount is applicable
    if (applicableDiscounts.isEmpty()) {
      return ImmutableList.of();
    }

    // From the applicable discount, get discount that matches most to the case (tag name)
    return findDiscountsMatchingPatriciaCase(
        applicableDiscounts, patriciaCase
    );
  }

  public static BigDecimal calculateTotalCharge(List<Discount> discounts,
                                                BigDecimal durationInHours,
                                                BigDecimal hourlyRate) {
    if (BigDecimal.ZERO.compareTo(hourlyRate) == 0) {
      return BigDecimal.ZERO;
    }
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
    // amount can be zero, if Unit Price was zero
    if (BigDecimal.ZERO.compareTo(amountWithoutDiscount) == 0) {
      return BigDecimal.ZERO;
    }
    return amountWithDiscount
        .subtract(amountWithoutDiscount)
        .divide(amountWithoutDiscount, 5, RoundingMode.HALF_UP)
        .multiply(BigDecimal.valueOf(100));
  }

  public static BigDecimal calculateHourlyRate(BigDecimal totalCharge, BigDecimal durationInHours) {
    // duration in hours can be zero, if set to be in the console or using zero amount work code
    if (BigDecimal.ZERO.compareTo(durationInHours) == 0) {
      return BigDecimal.ZERO;
    }
    return totalCharge.divide(durationInHours, 2, RoundingMode.HALF_UP);
  }

  public static BigDecimal calculateActualWorkedHoursNoExpRating(TimeGroup userPostedTime) {
    return toHours(
        DurationCalculator
            .of(userPostedTime)
            .useDurationFrom(DurationSource.SUM_TIME_ROWS)
            .disregardExperienceWeighting()
            .calculate()
    );
  }

  public static BigDecimal calculateActualWorkedHoursWithExpRating(TimeGroup userPostedTime) {
    return toHours(
        DurationCalculator
            .of(userPostedTime)
            .useDurationFrom(DurationSource.SUM_TIME_ROWS)
            .calculate()
    );
  }

  public static BigDecimal calculateChargeableWorkedHoursNoExpRating(TimeGroup userPostedTime) {
    return toHours(
        DurationCalculator
            .of(userPostedTime)
            .useDurationFrom(DurationSource.TIME_GROUP)
            .disregardExperienceWeighting()
            .calculate()
    );
  }

  public static BigDecimal calculateChargeableWorkedHoursWithExpRating(TimeGroup userPostedTime) {
    return toHours(
        DurationCalculator
            .of(userPostedTime)
            .useDurationFrom(DurationSource.TIME_GROUP)
            .calculate()
    );
  }

  public static boolean wasTotalDurationEdited(TimeGroup userPostedTimeGroup) {
    // check if user edited the total time by comparing the total time to the sum of the time on each row
    return !userPostedTimeGroup.getTotalDurationSecs()
        .equals(userPostedTimeGroup.getTimeRows().stream().mapToInt(TimeRow::getDurationSecs).sum());
  }

  private static BigDecimal toHours(long durationSecs) {
    return BigDecimal
        .valueOf(durationSecs)
        .divide(BigDecimal.valueOf(3600), 2, RoundingMode.HALF_UP);
  }

  private static List<Discount> findDiscountsMatchingPatriciaCase(List<Discount> discounts, Case patriciaCase) {
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
      // return the matching discount with highest priority
      return filterDiscountsByHighestPriority(matchingDiscounts);
    }

    return ImmutableList.of();
  }

  private static List<Discount> filterDiscountsByHighestPriority(List<Discount> discounts) {
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
    BigDecimal result = evaluateDiscountFormula(discountToApply, originalAmount);

    return originalAmount.subtract(result);
  }

  private static BigDecimal calculateMarkedUpBillingAmount(Discount discountToApply,
                                                           BigDecimal originalAmount) {
    BigDecimal result = evaluateDiscountFormula(discountToApply, originalAmount);

    return originalAmount.add(result);
  }

  private static BigDecimal evaluateDiscountFormula(Discount discountToApply, BigDecimal originalAmount) {
    return BigDecimal.valueOf(new ExpressionBuilder(discountToApply.priceChangeFormula()
        // change german decimal "," to "." and substitute variable name
        .replace('@', 'x').replace(',', '.'))
        .variable("x")
        .build()
        .setVariable("x", originalAmount.doubleValue())
        .evaluate());
  }
}

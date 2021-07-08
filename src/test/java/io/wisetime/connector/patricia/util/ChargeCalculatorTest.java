/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All rights reserved.
 */

package io.wisetime.connector.patricia.util;

import static io.wisetime.connector.patricia.PatriciaDao.Case;
import static io.wisetime.connector.patricia.PatriciaDao.Discount;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.javafaker.Faker;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import io.wisetime.connector.patricia.FakeEntities;
import io.wisetime.connector.patricia.RandomDataGenerator;
import io.wisetime.generated.connect.TimeGroup;
import io.wisetime.generated.connect.TimeRow;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * @author alvin.llobrera@practiceinsight.io
 */
class ChargeCalculatorTest {

  private static final RandomDataGenerator GENERATOR = new RandomDataGenerator();
  private static final FakeEntities FAKE_ENTITIES = new FakeEntities();
  private static final Faker FAKER = new Faker();

  @Test
  void findMostApplicableDiscount_should_find_discount_matching_to_the_case() {
    Case patriciaCase = GENERATOR.randomCase();

    Discount highestPriority = Discount.builder()
        .priority(10)
        .stateId(FAKER.crypto().md5())
        .priceChangeFormula("@")
        .build();
    Discount midPriority = Discount.builder()
        .priority(5)
        .stateId(patriciaCase.stateId())
        .priceChangeFormula("@")
        .build();
    Discount lowestPriority = Discount.builder()
        .priority(1)
        .stateId(FAKER.crypto().md5())
        .priceChangeFormula("@")
        .build();

    assertThat(
        ChargeCalculator.getMostApplicableDiscounts(
            ImmutableList.of(highestPriority, midPriority, lowestPriority), patriciaCase))
        .as("discount matching case should be selected")
        .contains(midPriority);
  }

  @Test
  void findMostApplicableDiscount_should_find_no_match() {
    Case patriciaCase = GENERATOR.randomCase();

    Discount highestPriority = Discount.builder()
        .priority(10)
        .stateId(FAKER.crypto().md5())
        .priceChangeFormula("@")
        .build();
    Discount midPriority = Discount.builder()
        .priority(5)
        .stateId(patriciaCase.stateId())
        .priceChangeFormula("@")
        .caseTypeId(FAKER.number().numberBetween(1, 100)) // not matching
        .build();
    Discount lowestPriority = Discount.builder()
        .priority(1)
        .stateId(FAKER.crypto().md5()) // not matching
        .priceChangeFormula("@")
        .caseTypeId(patriciaCase.caseTypeId())
        .build();

    assertThat(
        ChargeCalculator.getMostApplicableDiscounts(
            ImmutableList.of(highestPriority, midPriority, lowestPriority), patriciaCase))
        .as("No matching discount should be returned")
        .isEmpty();
  }

  @Test
  void findMostApplicableDiscount_should_return_empty_if_no_discount_applicable() {
    assertThat(Lists.newArrayList())
        .as("check when no discounts")
        .isEmpty();
  }

  @Test
  void calculateTotalCharge_with_pure_discount_formula() {
    Discount discount = Discount.builder()
        .priority(10)
        .stateId(FAKER.crypto().md5())
        .discountType(ChargeCalculator.DISCOUNT_TYPE_PURE)
        .priceChangeFormula("@ * 0.1")
        .build();

    assertThat(
        ChargeCalculator.calculateTotalCharge(ImmutableList.of(discount), new BigDecimal(8), BigDecimal.valueOf(10))
    )
        .as("should deduct discount amount from total charge amount by percentage")
        .isEqualByComparingTo(BigDecimal.valueOf(72));
  }

  @Test
  void calculateTotalCharge_with_pure_discount_formula_while_choosing_correct_discount() {
    Discount discount1 = Discount.builder()
        .priority(10)
        .amount(BigDecimal.ZERO)
        .stateId(FAKER.crypto().md5())
        .discountType(ChargeCalculator.DISCOUNT_TYPE_PURE)
        .priceChangeFormula("@")
        .build();
    Discount discount2 = Discount.builder()
        .priority(10)
        .amount(new BigDecimal(50))
        .stateId(FAKER.crypto().md5())
        .discountType(ChargeCalculator.DISCOUNT_TYPE_PURE)
        .priceChangeFormula("@ * 0.1")
        .build();
    Discount discount3 = Discount.builder()
        .priority(10)
        .amount(new BigDecimal(100))
        .stateId(FAKER.crypto().md5())
        .discountType(ChargeCalculator.DISCOUNT_TYPE_PURE)
        .priceChangeFormula("@ * 0.2")
        .build();

    assertThat(
        ChargeCalculator.calculateTotalCharge(
            ImmutableList.of(discount1, discount2, discount3), new BigDecimal(8), BigDecimal.valueOf(10))
    )
        .as("should deduct discount amount from total charge amount by percentage")
        .isEqualByComparingTo(BigDecimal.valueOf(72));
  }

  @Test
  void calculateTotalCharge_with_markup_discount_formula() {
    Discount discount = Discount.builder()
        .priority(10)
        .stateId(FAKER.crypto().md5())
        .discountType(ChargeCalculator.DISCOUNT_TYPE_MARKUP)
        .priceChangeFormula("@ * 0.1")
        .build();

    assertThat(
        ChargeCalculator.calculateTotalCharge(ImmutableList.of(discount), BigDecimal.valueOf(8.00), BigDecimal.valueOf(10))
    )
        .as("should add markup amount from total charge amount by percentage")
        .isEqualByComparingTo(BigDecimal.valueOf(88));
  }

  @Test
  void calculateTotalCharge_with_markup_discount_formula_while_choosing_correct_discount() {
    Discount discount1 = Discount.builder()
        .priority(10)
        .amount(BigDecimal.ZERO)
        .stateId(FAKER.crypto().md5())
        .discountType(ChargeCalculator.DISCOUNT_TYPE_MARKUP)
        .priceChangeFormula("@")
        .build();
    Discount discount2 = Discount.builder()
        .priority(10)
        .amount(new BigDecimal(50))
        .stateId(FAKER.crypto().md5())
        .discountType(ChargeCalculator.DISCOUNT_TYPE_MARKUP)
        .priceChangeFormula("@ * 0.1")
        .build();
    Discount discount3 = Discount.builder()
        .priority(10)
        .amount(new BigDecimal(100))
        .stateId(FAKER.crypto().md5())
        .discountType(ChargeCalculator.DISCOUNT_TYPE_MARKUP)
        .priceChangeFormula("@ * 0.2")
        .build();

    assertThat(
        ChargeCalculator.calculateTotalCharge(
            ImmutableList.of(discount1, discount2, discount3), new BigDecimal(8), BigDecimal.valueOf(10))
    )
        .as("should add markup amount from total charge amount by percentage")
        .isEqualByComparingTo(BigDecimal.valueOf(88));
  }

  @Test
  void calculateTotalCharge_unknownDiscountType() {
    Discount discount = Discount.builder()
        .priority(10)
        .stateId(FAKER.crypto().md5())
        .discountType(100)
        .priceChangeFormula("@*0.5")
        .build();

    assertThatThrownBy(() ->
        ChargeCalculator.calculateTotalCharge(ImmutableList.of(discount), BigDecimal.valueOf(8.00), BigDecimal.valueOf(10))
    )
        .as("discount type is not supported")
        .withFailMessage("Unknown discount type 100")
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void calculateDiscountPercentage() {
    assertThat(ChargeCalculator.calculateDiscountPercentage(BigDecimal.valueOf(100), BigDecimal.valueOf(75)))
        .as("amount deducted is 25 which is -25%")
        .isEqualByComparingTo(BigDecimal.valueOf(-25));

    assertThat(ChargeCalculator.calculateDiscountPercentage(BigDecimal.valueOf(200), BigDecimal.valueOf(300)))
        .as("amount added is 100 which is 50%")
        .isEqualByComparingTo(BigDecimal.valueOf(50));
  }

  @Test
  void calculateDiscountAmount() {
    assertThat(ChargeCalculator.calculateDiscountAmount(BigDecimal.valueOf(100), BigDecimal.valueOf(75)))
        .as("amount deducted is 25")
        .isEqualByComparingTo(BigDecimal.valueOf(-25));

    assertThat(ChargeCalculator.calculateDiscountAmount(BigDecimal.valueOf(200), BigDecimal.valueOf(300)))
        .as("amount added is 100")
        .isEqualByComparingTo(BigDecimal.valueOf(100));
  }

  @Test
  void calculateHourlyRate() {
    assertThat(ChargeCalculator.calculateHourlyRate(BigDecimal.valueOf(100), BigDecimal.valueOf(4)))
        .as("total charge / duration")
        .isEqualByComparingTo(BigDecimal.valueOf(25));
  }

  @Test
  void calculateActualWorkedHours() {
    TimeRow timeRow1 = FAKE_ENTITIES.randomTimeRow().durationSecs(800);
    TimeRow timeRow2 = FAKE_ENTITIES.randomTimeRow().durationSecs(200);

    // time groups can only have at most one tag and duration split strategy is deprecated
    TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup();
    timeGroup.setTags(ImmutableList.of(FAKE_ENTITIES.randomTag()));
    timeGroup.setTimeRows(ImmutableList.of(timeRow1, timeRow2));
    timeGroup.setTotalDurationSecs(1500);
    timeGroup.getUser().experienceWeightingPercent(50);

    assertThat(ChargeCalculator.calculateActualWorkedHoursNoExpRating(timeGroup))
        .isEqualByComparingTo(BigDecimal.valueOf(0.28)); // 1000 secs

    assertThat(ChargeCalculator.calculateActualWorkedHoursWithExpRating(timeGroup))
        .isEqualByComparingTo(BigDecimal.valueOf(0.14)); // 500 secs

    assertThat(ChargeCalculator.calculateChargeableWorkedHoursNoExpRating(timeGroup))
        .isEqualByComparingTo(BigDecimal.valueOf(0.42)); // 1500 secs

    assertThat(ChargeCalculator.calculateChargeableWorkedHoursWithExpRating(timeGroup))
        .isEqualByComparingTo(BigDecimal.valueOf(0.21)); // 750 secs
  }
}

/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All rights reserved.
 */

package io.wisetime.connector.patricia.util;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import com.github.javafaker.Faker;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import io.wisetime.connector.patricia.ImmutableDiscount;
import io.wisetime.connector.patricia.RandomDataGenerator;

import static io.wisetime.connector.patricia.PatriciaDao.Case;
import static io.wisetime.connector.patricia.PatriciaDao.Discount;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @author alvin.llobrera@practiceinsight.io
 */
class ChargeCalculatorTest {

  private static final RandomDataGenerator GENERATOR = new RandomDataGenerator();
  private static final Faker FAKER = new Faker();

  @Test
  void findMostApplicableDiscount_should_find_discount_matching_to_the_case() {
    Case patriciaCase = GENERATOR.randomCase();

    Discount highestPriority = ImmutableDiscount.builder()
        .priority(10)
        .stateId(FAKER.crypto().md5())
        .build();
    Discount midPriority = ImmutableDiscount.builder()
        .priority(5)
        .stateId(patriciaCase.stateId())
        .build();
    Discount lowestPriority = ImmutableDiscount.builder()
        .priority(1)
        .stateId(FAKER.crypto().md5())
        .build();

    assertThat(
        ChargeCalculator.getMostApplicableDiscount(
            ImmutableList.of(highestPriority, midPriority, lowestPriority), patriciaCase))
        .as("discount matching case should be selected")
        .contains(midPriority);
  }

  @Test
  void findMostApplicableDiscount_should_find_general_discount_with_highest_priority() {
    Case patriciaCase = GENERATOR.randomCase();

    Discount highestPriority = ImmutableDiscount.builder()
        .priority(10)
        .stateId(FAKER.crypto().md5())
        .build();
    Discount midPriority = ImmutableDiscount.builder()
        .priority(5)
        .stateId(patriciaCase.stateId())
        .caseTypeId(FAKER.number().numberBetween(1, 100)) // not matching
        .build();
    Discount lowestPriority = ImmutableDiscount.builder()
        .priority(1)
        .stateId(FAKER.crypto().md5()) // not matching
        .caseTypeId(patriciaCase.caseTypeId())
        .build();

    assertThat(
        ChargeCalculator.getMostApplicableDiscount(
            ImmutableList.of(highestPriority, midPriority, lowestPriority), patriciaCase))
        .as("expecting general discount with highest priority if discount did not match the case")
        .contains(highestPriority);
  }

  @Test
  void findMostApplicableDiscount_should_return_empty_if_no_discount_applicable() {
    assertThat(Lists.newArrayList())
        .as("check when no discounts")
        .isEmpty();
  }

  @Test
  void findMostApplicableDiscount_should_throw_ex_when_discount_has_same_priority() {
    Case patriciaCase = GENERATOR.randomCase();

    Discount highestPriority = ImmutableDiscount.builder()
        .priority(10)
        .stateId(FAKER.crypto().md5())
        .build();
    Discount midPriority1 = ImmutableDiscount.builder()
        .priority(5)
        .stateId(patriciaCase.stateId()) // matcing state id
        .build();
    Discount midPriority2 = ImmutableDiscount.builder()
        .priority(5)
        .caseTypeId(patriciaCase.caseTypeId()) // matching case type id
        .build();
    Discount lowestPriority = ImmutableDiscount.builder()
        .priority(1)
        .stateId(FAKER.crypto().md5())
        .build();

    assertThatThrownBy(
        () -> ChargeCalculator.getMostApplicableDiscount(
            ImmutableList.of(highestPriority, midPriority1, midPriority2, lowestPriority), patriciaCase))
        .as("matching discount should have no same priority")
        .withFailMessage("Indistinct discount policy for case/person combination detected. Please resolve.")
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void calculateTotalCharge_with_pure_discount_amount() {
    Discount discount = ImmutableDiscount.builder()
        .priority(10)
        .stateId(FAKER.crypto().md5())
        .discountType(ChargeCalculator.DISCOUNT_TYPE_PURE)
        .amount(BigDecimal.valueOf(10))
        .build();

    assertThat(
        ChargeCalculator.calculateTotalCharge(Optional.of(discount), BigDecimal.valueOf(8), BigDecimal.valueOf(10))
    )
        .as("should deduct discount amount from total charge amount")
        .isEqualByComparingTo(BigDecimal.valueOf(70));
  }

  @Test
  void calculateTotalCharge_with_pure_discount_percentage() {
    Discount discount = ImmutableDiscount.builder()
        .priority(10)
        .stateId(FAKER.crypto().md5())
        .discountType(ChargeCalculator.DISCOUNT_TYPE_PURE)
        .discountPercent(BigDecimal.valueOf(10))
        .build();

    assertThat(
        ChargeCalculator.calculateTotalCharge(Optional.of(discount), new BigDecimal(8), BigDecimal.valueOf(10))
    )
        .as("should deduct discount amount from total charge amount by percentage")
        .isEqualByComparingTo(BigDecimal.valueOf(72));
  }

  @Test
  void calculateTotalCharge_with_markup_discount_amount() {
    Discount discount = ImmutableDiscount.builder()
        .priority(10)
        .stateId(FAKER.crypto().md5())
        .discountType(ChargeCalculator.DISCOUNT_TYPE_MARKUP)
        .amount(BigDecimal.valueOf(10))
        .build();

    assertThat(
        ChargeCalculator.calculateTotalCharge(Optional.of(discount), BigDecimal.valueOf(8), BigDecimal.valueOf(10))
    )
        .as("should add markup amount from total charge amount")
        .isEqualByComparingTo(BigDecimal.valueOf(90));
  }

  @Test
  void calculateTotalCharge_with_markup_discount_percentage() {
    Discount discount = ImmutableDiscount.builder()
        .priority(10)
        .stateId(FAKER.crypto().md5())
        .discountType(ChargeCalculator.DISCOUNT_TYPE_MARKUP)
        .discountPercent(BigDecimal.valueOf(10))
        .build();

    assertThat(
        ChargeCalculator.calculateTotalCharge(Optional.of(discount), BigDecimal.valueOf(8.00), BigDecimal.valueOf(10))
    )
        .as("should add markup amount from total charge amount by percentage")
        .isEqualByComparingTo(BigDecimal.valueOf(88));
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
  void calculateDurationToHours() {
    assertThat(ChargeCalculator.calculateDurationToHours(9000))
        .as("duration secs / 3600")
        .isEqualByComparingTo(BigDecimal.valueOf(2.5));
  }
}

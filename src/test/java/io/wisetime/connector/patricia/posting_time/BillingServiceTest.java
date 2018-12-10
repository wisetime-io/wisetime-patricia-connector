package io.wisetime.connector.patricia.posting_time;


import com.github.javafaker.Faker;

import org.assertj.core.util.Lists;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import io.wisetime.connector.patricia.PatriciaDao;
import io.wisetime.connector.patricia.RandomDataGenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * @author vadym
 */
class BillingServiceTest {

  private static final RandomDataGenerator GENERATOR = new RandomDataGenerator();
  private static final Faker FAKER = new Faker();
  private static PatriciaDao patriciaDao;
  private static BillingService billingService;

  @BeforeAll
  public static void setup() {
    patriciaDao = Mockito.mock(PatriciaDao.class);
    billingService = new BillingService(patriciaDao);
  }

  @BeforeEach
  public void reset() {
    Mockito.reset(patriciaDao);
  }

  @Test
  public void calculateBilling_noCurrency() {
    PostTimeCommonParams commonParams = GENERATOR.randomPostTimeCommonParams();

    assertThatThrownBy(() -> billingService.calculateBilling(commonParams,
        FAKER.number().numberBetween(0, 1000), FAKER.number().numberBetween(0, 1000)))
        .hasMessage("Could not find external system currency for case %s", commonParams.caseName());
  }

  @Test
  public void calculateBilling_noHourlyRate() {
    PostTimeCommonParams commonParams = GENERATOR.randomPostTimeCommonParams();
    when(patriciaDao.findCurrency(commonParams.caseId())).thenReturn(Optional.of("USD"));

    assertThatThrownBy(() -> billingService.calculateBilling(commonParams,
        FAKER.number().numberBetween(0, 1000), FAKER.number().numberBetween(0, 1000)))
        .hasMessage("Could not find external system case hourly rate.");
  }

  @Test
  public void getDurationByPercentage() {
    assertThat(billingService.getDurationByPercentage(33, 50))
        .as("expected 50% rounding down")
        .isEqualTo(16);
  }

  @Test
  public void calculateDurationToHours() {
    assertThat(billingService.calculateDurationToHours(30 * 60))
        .as("Check 30 minutes")
        .isEqualTo(BigDecimal.valueOf(50, 2));
    assertThat(billingService.calculateDurationToHours( 60))
        .as("Check 1 minute (round half up)")
        .isEqualTo(BigDecimal.valueOf(2, 2));
  }

  @Test
  public void calculateDiscountedBillingAmount() {
    BigDecimal workedHours = BigDecimal.valueOf(2.50);
    BigDecimal hourlyRate = BigDecimal.valueOf(25);

    PatriciaDiscountRecord fixedDiscount = ImmutablePatriciaDiscountRecord.builder()
        .discountPercent(BigDecimal.ZERO)
        .amount(BigDecimal.valueOf(10))
        .build();
    assertThat(billingService.calculateDiscountedBillingAmount(fixedDiscount, workedHours, hourlyRate))
        .as("check fixed discount")
        .isEqualTo(BigDecimal.valueOf(5250, 2));

    PatriciaDiscountRecord percentageDiscount = ImmutablePatriciaDiscountRecord.builder()
        .discountPercent(BigDecimal.valueOf(10))
        .amount(BigDecimal.valueOf(10))
        .build();
    assertThat(billingService.calculateDiscountedBillingAmount(percentageDiscount, workedHours, hourlyRate))
        .as("check percentage discount")
        .isEqualTo(BigDecimal.valueOf(5625, 2));
  }

  @Test
  public void calculateMarkedUpBillingAmount() {
    BigDecimal workedHours = BigDecimal.valueOf(2.50);
    BigDecimal hourlyRate = BigDecimal.valueOf(25);

    PatriciaDiscountRecord fixedDiscount = ImmutablePatriciaDiscountRecord.builder()
        .discountPercent(BigDecimal.ZERO)
        .amount(BigDecimal.valueOf(10))
        .build();
    assertThat(billingService.calculateMarkedUpBillingAmount(fixedDiscount, workedHours, hourlyRate))
        .as("check fixed discount")
        .isEqualTo(BigDecimal.valueOf(7250, 2));

    PatriciaDiscountRecord percentageDiscount = ImmutablePatriciaDiscountRecord.builder()
        .discountPercent(BigDecimal.valueOf(10))
        .amount(BigDecimal.valueOf(10))
        .build();
    assertThat(billingService.calculateMarkedUpBillingAmount(percentageDiscount, workedHours, hourlyRate))
        .as("check percentage discount")
        .isEqualTo(BigDecimal.valueOf(6875, 2));
  }

  @Test
  public void computeWorkedHours() {
    int workedSecs = 9000;//2.5h
    BigDecimal hourlyRate = BigDecimal.valueOf(25);

    assertThat(billingService.computeWorkedHours(workedSecs, hourlyRate, Optional.empty()))
        .as("check without discount")
        .isEqualTo(ImmutableWorkedHoursComputation.builder()
            .totalHours(BigDecimal.valueOf(250, 2))
            .totalAmount(BigDecimal.valueOf(6250, 2))
            .discountAmount(BigDecimal.ZERO)
            .discountPercentage(BigDecimal.ZERO)
            .discountedHourlyRate(hourlyRate.setScale(2, BigDecimal.ROUND_HALF_UP))
            .build());

    PatriciaDiscountRecord pureDiscount = ImmutablePatriciaDiscountRecord.builder()
        .discountPercent(BigDecimal.ZERO)
        .amount(BigDecimal.valueOf(10))
        .discountType(BillingService.PURE_DISCOUNT)
        .build();
    assertThat(billingService.computeWorkedHours(workedSecs, hourlyRate, Optional.of(pureDiscount)))
        .as("check pure discount")
        .isEqualTo(ImmutableWorkedHoursComputation.builder()
            .totalHours(BigDecimal.valueOf(250, 2))
            .totalAmount(BigDecimal.valueOf(5250, 2))
            .discountAmount(BigDecimal.valueOf(-1000, 2))
            .discountPercentage(BigDecimal.valueOf(-1600000, 5))
            .discountedHourlyRate(BigDecimal.valueOf(2100, 2))
            .build());

    PatriciaDiscountRecord markUpDiscount = ImmutablePatriciaDiscountRecord.builder()
        .discountPercent(BigDecimal.valueOf(10))
        .amount(BigDecimal.valueOf(10))
        .discountType(BillingService.MARK_UP_DISCOUNT)
        .build();
    assertThat(billingService.computeWorkedHours(workedSecs, hourlyRate, Optional.of(markUpDiscount)))
        .as("check mark up discount")
        .isEqualTo(ImmutableWorkedHoursComputation.builder()
            .totalHours(BigDecimal.valueOf(250, 2))
            .totalAmount(BigDecimal.valueOf(6875, 2))
            .discountAmount(BigDecimal.valueOf(625, 2))
            .discountPercentage(BigDecimal.valueOf(1000000, 5))
            .discountedHourlyRate(BigDecimal.valueOf(2750, 2))
            .build());
  }

  @Test
  public void sortDiscountsByHighestPriority() {
    PatriciaDiscountRecord highestPriority = ImmutablePatriciaDiscountRecord.builder()
        .priority(10)
        .build();
    PatriciaDiscountRecord midPriority = ImmutablePatriciaDiscountRecord.builder()
        .priority(5)
        .build();
    PatriciaDiscountRecord lowestPriority = ImmutablePatriciaDiscountRecord.builder()
        .priority(1)
        .build();
    List<PatriciaDiscountRecord> discounts = Lists.newArrayList(highestPriority, midPriority, lowestPriority);
    Collections.shuffle(discounts);

    billingService.sortDiscountsByHighestPriority(discounts);

    assertThat(discounts)
        .as("check discounts ordered by priority")
        .containsExactly(highestPriority, midPriority, lowestPriority);
  }

  @Test
  public void assertDiscountHasNoSamePriority() {
    PatriciaDiscountRecord priority10 = ImmutablePatriciaDiscountRecord.builder()
        .priority(10)
        .build();
    PatriciaDiscountRecord priority1 = ImmutablePatriciaDiscountRecord.builder()
        .priority(1)
        .build();

    //same discount should not trigger assert
    billingService.assertDiscountHasNoSamePriority(priority10, Arrays.asList(priority10, priority1));

    //no discount with same priority
    billingService.assertDiscountHasNoSamePriority(ImmutablePatriciaDiscountRecord.builder()
        .priority(5)
        .build(), Arrays.asList(priority10, priority1));

    PatriciaDiscountRecord anotherDiscountPriority10 = ImmutablePatriciaDiscountRecord.builder()
        .priority(10)
        .stateId(FAKER.lorem().word())
        .build();
    assertThatThrownBy(() -> billingService.assertDiscountHasNoSamePriority(anotherDiscountPriority10,
        Arrays.asList(priority10, priority1)))
        .as("there is another discount with same priority - exception expected")
        .hasMessage("Indistinct discount policy for case/person combination detected. Please resolve.");
  }

  @Test
  public void findDiscountMatchingPatriciaCase() {
    int caseId = FAKER.number().numberBetween(1, 10000);
    PatriciaCaseRecord patriciaCaseRecord = GENERATOR.randomPatriciaCaseRecord();
    PatriciaDiscountRecord notMatchingDiscount = ImmutablePatriciaDiscountRecord.builder()
        .priority(10)
        .caseTypeId(patriciaCaseRecord.caseTypeId() + 1)
        .applicationTypeId(patriciaCaseRecord.appId() + 1)
        .stateId(FAKER.crypto().md5())
        .build();
    PatriciaDiscountRecord matches = ImmutablePatriciaDiscountRecord.builder()
        .priority(10)
        .caseTypeId(patriciaCaseRecord.caseTypeId())
        .applicationTypeId(patriciaCaseRecord.appId())
        .stateId(patriciaCaseRecord.stateId())
        .build();
    PatriciaDiscountRecord nullFilters = ImmutablePatriciaDiscountRecord.builder()
        .priority(10)
        .caseTypeId(null)
        .applicationTypeId(null)
        .stateId(null)
        .build();

    assertThat(billingService.findDiscountMatchingPatriciaCase(
        Collections.singletonList(matches), caseId))
        .as("check when no record in db for requested case")
        .isEmpty();

    when(patriciaDao.findPatCaseData(caseId)).thenReturn(Optional.of(patriciaCaseRecord));

    assertThat(billingService.findDiscountMatchingPatriciaCase(Collections.singletonList(notMatchingDiscount), caseId))
        .as("check when discount not matched")
        .isEmpty();


    assertThat(billingService.findDiscountMatchingPatriciaCase(
        Collections.singletonList(nullFilters), caseId))
        .as("check when case type null")
        .contains(nullFilters);


    assertThat(billingService.findDiscountMatchingPatriciaCase(
        Collections.singletonList(matches), caseId))
        .as("check when case type matched")
        .contains(matches);
  }

  @Test
  public void findMostApplicableDiscount() {
    String workCodeId = FAKER.crypto().md5();
    int caseId = FAKER.number().numberBetween(1, 10000);

    PatriciaCaseRecord patriciaCaseRecord = GENERATOR.randomPatriciaCaseRecord();

    PatriciaDiscountRecord highestPriority = ImmutablePatriciaDiscountRecord.builder()
        .priority(10)
        .stateId(FAKER.crypto().md5())
        .build();
    PatriciaDiscountRecord midPriority = ImmutablePatriciaDiscountRecord.builder()
        .priority(5)
        .stateId(patriciaCaseRecord.stateId())
        .build();
    PatriciaDiscountRecord lowestPriority = ImmutablePatriciaDiscountRecord.builder()
        .priority(1)
        .stateId(FAKER.crypto().md5())
        .build();

    assertThat(billingService.findMostApplicableDiscount(workCodeId, caseId))
        .as("check when no discounts")
        .isEmpty();

    when(patriciaDao.findDiscountRecords(workCodeId, caseId))
        .thenReturn(Arrays.asList(lowestPriority, midPriority, highestPriority));

    assertThat(billingService.findMostApplicableDiscount(workCodeId, caseId))
        .as("check when no discounts matched - expecting general discount with highest priority")
        .contains(highestPriority);

    when(patriciaDao.findPatCaseData(caseId))
        .thenReturn(Optional.of(patriciaCaseRecord));

    assertThat(billingService.findMostApplicableDiscount(workCodeId, caseId))
        .as("check discount matches case")
        .contains(midPriority);
  }
}
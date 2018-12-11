package io.wisetime.connector.patricia;

import com.google.inject.Guice;
import com.google.inject.Injector;

import com.github.javafaker.Faker;

import org.apache.commons.lang3.StringUtils;
import org.assertj.core.util.Lists;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import io.wisetime.connector.config.RuntimeConfig;
import io.wisetime.generated.connect.UpsertTagRequest;

import static io.wisetime.connector.patricia.ConnectorLauncher.PatriciaConnectorConfigKey;
import static io.wisetime.connector.patricia.PatriciaDao.Case;
import static io.wisetime.connector.patricia.PatriciaDao.Discount;
import static io.wisetime.connector.patricia.PatriciaDao.PostTimeData;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * @author vadym
 */
class PatriciaDaoHelperTest {

  private static final RandomDataGenerator GENERATOR = new RandomDataGenerator();
  private static final Faker FAKER = new Faker();

  private static PatriciaDao patriciaDaoWithMockQuery;

  @BeforeAll
  static void setup() {
    RuntimeConfig.setProperty(PatriciaConnectorConfigKey.PATRICIA_JDBC_URL, "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1");
    RuntimeConfig.setProperty(PatriciaConnectorConfigKey.PATRICIA_JDBC_USERNAME, "test");
    RuntimeConfig.setProperty(PatriciaConnectorConfigKey.PATRICIA_JDBC_PASSWORD, "test");

    final Injector injector = Guice.createInjector(
        new ConnectorLauncher.PatriciaDbModule()
    );

    patriciaDaoWithMockQuery = spy(injector.getInstance(PatriciaDao.class));
  }

  @Test
  public void toUpsertTagRequest() {
    Case patCase = new RandomDataGenerator().randomCase();
    String path = new Faker().lorem().word();
    UpsertTagRequest upsertTagRequest = patCase.toUpsertTagRequest(path);
    assertThat(upsertTagRequest)
        .as("check patricia case path to UpsertTagRequest mapping")
        .returns(path, UpsertTagRequest::getPath)
        .as("check patricia case name to UpsertTagRequest mapping")
        .returns(patCase.caseNumber(), UpsertTagRequest::getName)
        .as("check patricia case description to UpsertTagRequest mapping")
        .returns(StringUtils.trimToEmpty(patCase.caseCatchWord()), UpsertTagRequest::getDescription);
  }

  @Test
  void calculateBilling_noCurrency() {
    PostTimeData commonParams = GENERATOR.randomPostTimeCommonParams();

    assertThatThrownBy(() -> patriciaDaoWithMockQuery.calculateBilling(commonParams,
        FAKER.number().numberBetween(0, 1000), FAKER.number().numberBetween(0, 1000)))
        .hasMessage("Could not find external system currency for case %s", commonParams.caseName());
  }

  @Test
  void calculateBilling_noHourlyRate() {
    PostTimeData commonParams = GENERATOR.randomPostTimeCommonParams();
    when(patriciaDaoWithMockQuery.findCurrency(commonParams.caseId())).thenReturn(Optional.of("USD"));

    assertThatThrownBy(() -> patriciaDaoWithMockQuery.calculateBilling(commonParams,
        FAKER.number().numberBetween(0, 1000), FAKER.number().numberBetween(0, 1000)))
        .hasMessage("Could not find external system case hourly rate.");
  }

  @Test
  void getDurationByPercentage() {
    assertThat(patriciaDaoWithMockQuery.getDurationByPercentage(33, 50))
        .as("expected 50% rounding down")
        .isEqualTo(16);
  }

  @Test
  void calculateDurationToHours() {
    assertThat(patriciaDaoWithMockQuery.calculateDurationToHours(30 * 60))
        .as("Check 30 minutes")
        .isEqualTo(BigDecimal.valueOf(50, 2));
    assertThat(patriciaDaoWithMockQuery.calculateDurationToHours(60))
        .as("Check 1 minute (round half up)")
        .isEqualTo(BigDecimal.valueOf(2, 2));
  }

  @Test
  void calculateDiscountedBillingAmount() {
    BigDecimal workedHours = BigDecimal.valueOf(2.50);
    BigDecimal hourlyRate = BigDecimal.valueOf(25);

    Discount fixedDiscount = ImmutableDiscount.builder()
        .discountPercent(BigDecimal.ZERO)
        .amount(BigDecimal.valueOf(10))
        .build();
    assertThat(patriciaDaoWithMockQuery.calculateDiscountedBillingAmount(fixedDiscount, workedHours, hourlyRate))
        .as("check fixed discount")
        .isEqualTo(BigDecimal.valueOf(5250, 2));

    Discount percentageDiscount = ImmutableDiscount.builder()
        .discountPercent(BigDecimal.valueOf(10))
        .amount(BigDecimal.valueOf(10))
        .build();
    assertThat(patriciaDaoWithMockQuery.calculateDiscountedBillingAmount(percentageDiscount, workedHours, hourlyRate))
        .as("check percentage discount")
        .isEqualTo(BigDecimal.valueOf(5625, 2));
  }

  @Test
  void calculateMarkedUpBillingAmount() {
    BigDecimal workedHours = BigDecimal.valueOf(2.50);
    BigDecimal hourlyRate = BigDecimal.valueOf(25);

    Discount fixedDiscount = ImmutableDiscount.builder()
        .discountPercent(BigDecimal.ZERO)
        .amount(BigDecimal.valueOf(10))
        .build();
    assertThat(patriciaDaoWithMockQuery.calculateMarkedUpBillingAmount(fixedDiscount, workedHours, hourlyRate))
        .as("check fixed discount")
        .isEqualTo(BigDecimal.valueOf(7250, 2));

    Discount percentageDiscount = ImmutableDiscount.builder()
        .discountPercent(BigDecimal.valueOf(10))
        .amount(BigDecimal.valueOf(10))
        .build();
    assertThat(patriciaDaoWithMockQuery.calculateMarkedUpBillingAmount(percentageDiscount, workedHours, hourlyRate))
        .as("check percentage discount")
        .isEqualTo(BigDecimal.valueOf(6875, 2));
  }

  @Test
  void computeWorkedHours() {
    int workedSecs = 9000; //2.5h
    BigDecimal hourlyRate = BigDecimal.valueOf(25);

    assertThat(patriciaDaoWithMockQuery.computeWorkedHours(workedSecs, hourlyRate, Optional.empty()))
        .as("check without discount")
        .isEqualTo(ImmutableWorkedHoursComputation.builder()
            .totalHours(BigDecimal.valueOf(250, 2))
            .totalAmount(BigDecimal.valueOf(6250, 2))
            .discountAmount(BigDecimal.ZERO)
            .discountPercentage(BigDecimal.ZERO)
            .discountedHourlyRate(hourlyRate.setScale(2, BigDecimal.ROUND_HALF_UP))
            .build());

    Discount  pureDiscount = ImmutableDiscount.builder()
        .discountPercent(BigDecimal.ZERO)
        .amount(BigDecimal.valueOf(10))
        .discountType(PatriciaDao.PURE_DISCOUNT)
        .build();
    assertThat(patriciaDaoWithMockQuery.computeWorkedHours(workedSecs, hourlyRate, Optional.of(pureDiscount)))
        .as("check pure discount")
        .isEqualTo(ImmutableWorkedHoursComputation.builder()
            .totalHours(BigDecimal.valueOf(250, 2))
            .totalAmount(BigDecimal.valueOf(5250, 2))
            .discountAmount(BigDecimal.valueOf(-1000, 2))
            .discountPercentage(BigDecimal.valueOf(-1600000, 5))
            .discountedHourlyRate(BigDecimal.valueOf(2100, 2))
            .build());

    Discount  markUpDiscount = ImmutableDiscount.builder()
        .discountPercent(BigDecimal.valueOf(10))
        .amount(BigDecimal.valueOf(10))
        .discountType(PatriciaDao.MARK_UP_DISCOUNT)
        .build();
    assertThat(patriciaDaoWithMockQuery.computeWorkedHours(workedSecs, hourlyRate, Optional.of(markUpDiscount)))
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
    Discount highestPriority = ImmutableDiscount.builder()
        .priority(10)
        .build();
    Discount midPriority = ImmutableDiscount.builder()
        .priority(5)
        .build();
    Discount lowestPriority = ImmutableDiscount.builder()
        .priority(1)
        .build();
    List<Discount> discounts = Lists.newArrayList(highestPriority, midPriority, lowestPriority);
    Collections.shuffle(discounts);

    patriciaDaoWithMockQuery.sortDiscountsByHighestPriority(discounts);

    assertThat(discounts)
        .as("check discounts ordered by priority")
        .containsExactly(highestPriority, midPriority, lowestPriority);
  }

  @Test
  public void assertDiscountHasNoSamePriority() {
    Discount priority10 = ImmutableDiscount.builder()
        .priority(10)
        .build();
    Discount priority1 = ImmutableDiscount.builder()
        .priority(1)
        .build();

    //same discount should not trigger assert
    patriciaDaoWithMockQuery.assertDiscountHasNoSamePriority(priority10, Arrays.asList(priority10, priority1));

    //no discount with same priority
    patriciaDaoWithMockQuery.assertDiscountHasNoSamePriority(ImmutableDiscount.builder()
        .priority(5)
        .build(), Arrays.asList(priority10, priority1));

    Discount anotherDiscountPriority10 = ImmutableDiscount.builder()
        .priority(10)
        .stateId(FAKER.lorem().word())
        .build();
    assertThatThrownBy(() -> patriciaDaoWithMockQuery.assertDiscountHasNoSamePriority(anotherDiscountPriority10,
        Arrays.asList(priority10, priority1)))
        .as("there is another discount with same priority - exception expected")
        .hasMessage("Indistinct discount policy for case/person combination detected. Please resolve.");
  }

  @Test
  public void findDiscountMatchingPatriciaCase() {
    int caseId = FAKER.number().numberBetween(1, 10000);
    Case patriciaCaseRecord = GENERATOR.randomCase();
    Discount notMatchingDiscount = ImmutableDiscount.builder()
        .priority(10)
        .caseTypeId(patriciaCaseRecord.caseTypeId() + 1)
        .applicationTypeId(patriciaCaseRecord.appId() + 1)
        .stateId(FAKER.crypto().md5())
        .build();
    Discount matches = ImmutableDiscount.builder()
        .priority(10)
        .caseTypeId(patriciaCaseRecord.caseTypeId())
        .applicationTypeId(patriciaCaseRecord.appId())
        .stateId(patriciaCaseRecord.stateId())
        .build();
    Discount nullFilters = ImmutableDiscount.builder()
        .priority(10)
        .caseTypeId(null)
        .applicationTypeId(null)
        .stateId(null)
        .build();

    assertThat(patriciaDaoWithMockQuery.findDiscountMatchingPatriciaCase(
        Collections.singletonList(matches), caseId))
        .as("check when no record in db for requested case")
        .isEmpty();

    when(patriciaDaoWithMockQuery.findPatCaseData(caseId)).thenReturn(Optional.of(patriciaCaseRecord));

    assertThat(patriciaDaoWithMockQuery
        .findDiscountMatchingPatriciaCase(Collections.singletonList(notMatchingDiscount), caseId))
        .as("check when discount not matched")
        .isEmpty();


    assertThat(patriciaDaoWithMockQuery.findDiscountMatchingPatriciaCase(
        Collections.singletonList(nullFilters), caseId))
        .as("check when case type null")
        .contains(nullFilters);


    assertThat(patriciaDaoWithMockQuery.findDiscountMatchingPatriciaCase(
        Collections.singletonList(matches), caseId))
        .as("check when case type matched")
        .contains(matches);
  }

  @Test
  public void findMostApplicableDiscount() {
    String workCodeId = FAKER.crypto().md5();
    int caseId = FAKER.number().numberBetween(1, 10000);

    Case patriciaCaseRecord = GENERATOR.randomCase();

    Discount highestPriority = ImmutableDiscount.builder()
        .priority(10)
        .stateId(FAKER.crypto().md5())
        .build();
    Discount midPriority = ImmutableDiscount.builder()
        .priority(5)
        .stateId(patriciaCaseRecord.stateId())
        .build();
    Discount lowestPriority = ImmutableDiscount.builder()
        .priority(1)
        .stateId(FAKER.crypto().md5())
        .build();

    assertThat(patriciaDaoWithMockQuery.findMostApplicableDiscount(workCodeId, caseId))
        .as("check when no discounts")
        .isEmpty();

    when(patriciaDaoWithMockQuery.findDiscountRecords(workCodeId, caseId))
        .thenReturn(Arrays.asList(lowestPriority, midPriority, highestPriority));

    assertThat(patriciaDaoWithMockQuery.findMostApplicableDiscount(workCodeId, caseId))
        .as("check when no discounts matched - expecting general discount with highest priority")
        .contains(highestPriority);

    when(patriciaDaoWithMockQuery.findPatCaseData(caseId))
        .thenReturn(Optional.of(patriciaCaseRecord));

    assertThat(patriciaDaoWithMockQuery.findMostApplicableDiscount(workCodeId, caseId))
        .as("check discount matches case")
        .contains(midPriority);
  }
}
/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.patricia;

import com.github.javafaker.Faker;

import io.wisetime.connector.patricia.PatriciaDao.WorkCode;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static io.wisetime.connector.patricia.PatriciaDao.Case;

/**
 * @author vadym
 */
public class RandomDataGenerator {

  private static final Faker FAKER = new Faker();

  public Case randomCase() {
    return randomCase(FAKER.crypto().md5());
  }

  public Case randomCase(String caseNumber) {
    return ImmutableCase.builder()
        .caseId(FAKER.number().numberBetween(1, 10_000))
        .caseCatchWord(FAKER.lorem().word())
        .caseNumber(caseNumber)
        .appId(FAKER.number().numberBetween(1, 10_000))
        .caseTypeId(FAKER.number().numberBetween(1, 10_000))
        .stateId(FAKER.bothify("?#"))
        .build();
  }

  public List<Case> randomCase(int count) {
    return randomEntities(this::randomCase, count, count);
  }

  public WorkCode randomWorkCode() {
    return ImmutableWorkCode.builder()
        .workCodeId(FAKER.numerify("wc#####"))
        .workCodeText(FAKER.numerify("text-#####"))
        .build();
  }

  public List<WorkCode> randomWorkCodes(int count) {
    return randomEntities(this::randomWorkCode, count, count);
  }

  private <T> List<T> randomEntities(final Supplier<T> supplier, final int min, final int max) {
    return IntStream
        .range(0, FAKER.random().nextInt(min, max))
        .mapToObj(i -> supplier.get())
        .collect(Collectors.toList());
  }
}

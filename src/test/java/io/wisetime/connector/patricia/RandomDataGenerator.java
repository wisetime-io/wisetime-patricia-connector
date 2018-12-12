/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.patricia;

import com.github.javafaker.Faker;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import io.wisetime.generated.connect.Tag;
import io.wisetime.generated.connect.TimeGroup;
import io.wisetime.generated.connect.TimeRow;
import io.wisetime.generated.connect.User;

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

  public TimeGroup randomTimeGroup() {
    TimeGroup timeGroup = new TimeGroup();
    timeGroup.setCallerKey(FAKER.crypto().md5());
    timeGroup.setTags(randomTagList());
    timeGroup.setTimeRows(randomTimeRowList());
    timeGroup.setUser(randomUser());
    timeGroup.setTotalDurationSecs(FAKER.random().nextInt(0, 10_000));
    timeGroup.setDurationSplitStrategy(TimeGroup.DurationSplitStrategyEnum.DIVIDE_BETWEEN_TAGS);
    return timeGroup;
  }

  private List<Tag> randomTagList() {
    return Stream.generate(this::randomTag)
        .limit(FAKER.number().numberBetween(2, 10))
        .collect(Collectors.toList());
  }

  public Tag randomTag() {
    Tag tag = new Tag();
    tag.setName(FAKER.name().name());
    return tag;
  }

  private List<TimeRow> randomTimeRowList() {
    return Stream.generate(this::randomTimeRow)
        .limit(FAKER.number().numberBetween(2, 10))
        .collect(Collectors.toList());
  }

  private TimeRow randomTimeRow() {
    TimeRow timeRow = new TimeRow();
    timeRow.setDurationSecs(FAKER.number().numberBetween(60, 600));
    return timeRow;
  }

  private User randomUser() {
    User user = new User();
    user.setExternalId(FAKER.crypto().md5());
    user.setExperienceWeightingPercent(FAKER.number().numberBetween(0, 100));
    user.setEmail(FAKER.internet().emailAddress());
    return user;
  }

  private <T> List<T> randomEntities(final Supplier<T> supplier, final int min, final int max) {
    return IntStream
        .range(0, FAKER.random().nextInt(min, max))
        .mapToObj(i -> supplier.get())
        .collect(Collectors.toList());
  }
}

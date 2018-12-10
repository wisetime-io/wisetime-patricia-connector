/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.patricia;

import com.github.javafaker.Faker;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.wisetime.connector.patricia.posting_time.ImmutablePatriciaCaseRecord;
import io.wisetime.connector.patricia.posting_time.ImmutablePostTimeCommonParams;
import io.wisetime.connector.patricia.posting_time.PatriciaCaseRecord;
import io.wisetime.connector.patricia.posting_time.PostTimeCommonParams;
import io.wisetime.generated.connect.Tag;
import io.wisetime.generated.connect.TimeGroup;
import io.wisetime.generated.connect.TimeRow;
import io.wisetime.generated.connect.User;

/**
 * @author vadym
 */
public class RandomDataGenerator {

  private static final Faker FAKER = new Faker();

  public PatriciaDao.PatriciaCase randomCase() {
    return ImmutablePatriciaCase.builder()
        .id(FAKER.number().numberBetween(1, 10000))
        .caseCatchWord(FAKER.lorem().word())
        .caseNumber(FAKER.crypto().md5())
        .build();
  }

  public PostTimeCommonParams randomPostTimeCommonParams() {
    return ImmutablePostTimeCommonParams.builder()
        .caseName(FAKER.name().name())
        .recordalDate(FAKER.date().birthday().toString())
        .workCodeId(FAKER.numerify("workCode######"))
        .loginId(FAKER.numerify("login######"))
        .experienceWeightingPercent(FAKER.number().numberBetween(0, 100))
        .caseId(FAKER.number().numberBetween(1, 10000))
        .build();
  }

  public PatriciaCaseRecord randomPatriciaCaseRecord() {
    return ImmutablePatriciaCaseRecord.builder()
        .appId(FAKER.number().numberBetween(1, 10000))
        .caseId(FAKER.number().numberBetween(1, 10000))
        .caseTypeId(FAKER.number().numberBetween(1, 10000))
        .stateId(FAKER.crypto().md5())
        .build();
  }

  public TimeGroup randomTimeGroup() {
    TimeGroup timeGroup = new TimeGroup();
    timeGroup.setCallerKey(FAKER.crypto().md5());
    timeGroup.setTags(randomTagList());
    timeGroup.setTimeRows(randomTimeRowList());
    timeGroup.setUser(randomUser());
    timeGroup.setTotalDurationSecs(FAKER.random().nextInt(0, 10000));
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
}

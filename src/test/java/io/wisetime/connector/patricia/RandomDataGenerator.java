/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.patricia;

import com.github.javafaker.Faker;

/**
 * @author vadym
 */
class RandomDataGenerator {

  private static final Faker FAKER = new Faker();

  PatriciaDao.PatriciaCase randomCase() {
    return ImmutablePatriciaCase.builder()
        .id(FAKER.number().numberBetween(1, 10000))
        .caseCatchWord(FAKER.lorem().word())
        .caseNumber(FAKER.crypto().md5())
        .build();
  }

}

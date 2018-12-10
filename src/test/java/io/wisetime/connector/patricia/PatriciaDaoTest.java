package io.wisetime.connector.patricia;

import com.github.javafaker.Faker;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;

import io.wisetime.generated.connect.UpsertTagRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author vadym
 */
class PatriciaDaoTest {

  @Test
  public void toUpsertTagRequest() {
    PatriciaDao.PatriciaCase patriciaCase = new RandomDataGenerator().randomCase();
    String path = new Faker().lorem().word();
    UpsertTagRequest upsertTagRequest = patriciaCase.toUpsertTagRequest(path);
    assertThat(upsertTagRequest)
        .as("check patricia case path to UpsertTagRequest mapping")
        .returns(path, UpsertTagRequest::getPath)
        .as("check patricia case name to UpsertTagRequest mapping")
        .returns(patriciaCase.caseNumber(), UpsertTagRequest::getName)
        .as("check patricia case description to UpsertTagRequest mapping")
        .returns(StringUtils.trimToEmpty(patriciaCase.caseCatchWord()), UpsertTagRequest::getDescription);
  }
}
package io.wisetime.connector.patricia.util;

import static org.assertj.core.api.Assertions.assertThat;

import io.wisetime.connector.patricia.ImmutableWorkCode;
import io.wisetime.connector.patricia.PatriciaDao.WorkCode;
import java.util.List;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.Test;

/**
 * @author yehor.lashkul
 */
class HashFunctionTest {

  private final HashFunction hashFunction = new HashFunction();

  @Test
  void hashStrings() {
    assertThat(hashFunction.hashStrings(List.of("a", "b", "c")))
        .as("generated hash is an md5 hash of concatenated strings")
        .isEqualTo(DigestUtils.md5Hex("abc"))
        .isEqualTo("900150983cd24fb0d6963f7d28e17f72");
  }

  @Test
  void hashActivityTypes() {
    assertThat(hashFunction.hashWorkCodes(List.of()))
        .as("generated hash is an md5 hash of empty string")
        .isEqualTo(DigestUtils.md5Hex(""));

    final List<WorkCode> list = List.of(
        ImmutableWorkCode.builder().workCodeId("id-1").workCodeText("text-1").build(),
        ImmutableWorkCode.builder().workCodeId("id-2").workCodeText("text-2").build());
    assertThat(hashFunction.hashWorkCodes(list))
        .as("generated hash is an md5 hash of simplified text representation of the list")
        .isEqualTo(DigestUtils.md5Hex("id-1text-1id-2text-2"));
  }
}

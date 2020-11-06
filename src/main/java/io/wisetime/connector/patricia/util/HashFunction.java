package io.wisetime.connector.patricia.util;

import com.google.inject.Singleton;
import io.wisetime.connector.patricia.PatriciaDao.WorkCode;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.commons.codec.digest.DigestUtils;

/**
 * @author yehor.lashkul
 */
@Singleton
public class HashFunction {

  public String hashStrings(List<String> strings) {
    return hash(strings, Function.identity());
  }

  public String hashWorkCodes(List<WorkCode> workCodes) {
    return hash(workCodes, workCode -> workCode.workCodeId() + workCode.workCodeText());
  }

  private <T> String hash(List<T> list, Function<T, String> toString) {
    final String listString = list.stream().map(toString).collect(Collectors.joining());
    return DigestUtils.md5Hex(listString);
  }
}

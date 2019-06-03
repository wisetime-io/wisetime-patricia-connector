/*
 * Copyright (c) 2019 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.patricia;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author vadym
 */
public class ConnectorLauncherTest {

  @Test
  void buildSafeJdbcUrl_postgres() {
    String samplePostgresUrl = "jdbc:postgresql://localhost:5432/test?user=fred&password=secret&ssl=true";
    assertThat(new ConnectorLauncher.PatriciaDbModule().formatForLogging(samplePostgresUrl))
        .as("password and user have to be excluded from output")
        .isEqualTo("host: localhost, port: 5432, database name: test");
  }

  @Test
  void buildSafeJdbcUrl_mysql() {
    String samplePostgresUrl = "jdbc:mysql://user:password@localhost:3306/test";
    assertThat(new ConnectorLauncher.PatriciaDbModule().formatForLogging(samplePostgresUrl))
        .as("password and user have to be excluded from output")
        .isEqualTo("host: localhost, port: 3306, database name: test");
  }

  @Test
  void buildSafeJdbcUrl_sqlServer() {
    String samplePostgresUrl = "jdbc:sqlserver://localhost:1433;user=MyUserName;password=secure;";
    assertThat(new ConnectorLauncher.PatriciaDbModule().formatForLogging(samplePostgresUrl))
        .as("password and user have to be excluded from output")
        .isEqualTo("host: localhost, port: 1433, database name: UNKNOWN");
  }

  @Test
  void buildSafeJdbcUrl_noPort() {
    String samplePostgresUrl = "jdbc:postgresql://localhost/test?user=fred&password=secret&ssl=true";
    assertThat(new ConnectorLauncher.PatriciaDbModule().formatForLogging(samplePostgresUrl))
        .as("check with default port")
        .isEqualTo("host: localhost, port: DEFAULT, database name: test");
  }
}
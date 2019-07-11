/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.patricia;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.wisetime.connector.ConnectorController;
import io.wisetime.connector.config.RuntimeConfig;
import io.wisetime.connector.config.RuntimeConfigKey;
import java.util.concurrent.TimeUnit;

/**
 * Connector application entry point.
 *
 * @author vadym
 */
public class ConnectorLauncher {

  public static void main(final String... args) throws Exception {
    ConnectorController connectorController = buildConnectorController();
    connectorController.start();
  }

  public static ConnectorController buildConnectorController() {
    return ConnectorController.newBuilder()
        .withWiseTimeConnector(Guice.createInjector(new PatriciaDbModule()).getInstance(PatriciaConnector.class))
        .build();
  }

  /**
   * Configuration keys for the WiseTime Patricia Connector.
   *
   * @author vadym
   */
  public enum PatriciaConnectorConfigKey implements RuntimeConfigKey {

    //required
    PATRICIA_JDBC_URL("PATRICIA_JDBC_URL"),
    PATRICIA_JDBC_USERNAME("PATRICIA_JDBC_USERNAME"),
    PATRICIA_JDBC_PASSWORD("PATRICIA_JDBC_PASSWORD"),
    PATRICIA_ROLE_TYPE_ID("PATRICIA_ROLE_TYPE_ID"),

    //optional
    WORK_CODES_ZERO_CHARGE("WORK_CODES_ZERO_CHARGE"),
    TAG_UPSERT_PATH("TAG_UPSERT_PATH"),
    TAG_UPSERT_BATCH_SIZE("TAG_UPSERT_BATCH_SIZE"),
    TIMEZONE("TIMEZONE"),
    ADD_SUMMARY_TO_NARRATIVE("ADD_SUMMARY_TO_NARRATIVE"),
    INVOICE_COMMENT_OVERRIDE("INVOICE_COMMENT_OVERRIDE");

    private final String configKey;

    PatriciaConnectorConfigKey(final String configKey) {
      this.configKey = configKey;
    }

    @Override
    public String getConfigKey() {
      return configKey;
    }
  }

  /**
   * Bind the Patricia database connection via DI.
   */
  public static class PatriciaDbModule extends AbstractModule {

    @Override
    protected void configure() {
      final HikariConfig hikariConfig = new HikariConfig();

      hikariConfig.setJdbcUrl(
          RuntimeConfig.getString(PatriciaConnectorConfigKey.PATRICIA_JDBC_URL)
              .orElseThrow(() -> new RuntimeException("Missing required PATRICIA_JDBC_URL configuration"))
      );

      hikariConfig.setUsername(
          RuntimeConfig.getString(PatriciaConnectorConfigKey.PATRICIA_JDBC_USERNAME)
              .orElseThrow(() -> new RuntimeException("Missing required PATRICIA_JDBC_USERNAME configuration"))
      );

      hikariConfig.setPassword(
          RuntimeConfig.getString(PatriciaConnectorConfigKey.PATRICIA_JDBC_PASSWORD)
              .orElseThrow(() -> new RuntimeException("Missing required PATRICIA_JDBC_PASSWORD configuration"))
      );
      hikariConfig.setConnectionTimeout(TimeUnit.MINUTES.toMillis(1));
      hikariConfig.setMaximumPoolSize(10);

      bind(HikariDataSource.class).toInstance(new HikariDataSource(hikariConfig));
    }

  }
}

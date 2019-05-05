/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.patricia;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

import io.wisetime.connector.Connector;
import io.wisetime.connector.ConnectorController;
import io.wisetime.connector.config.RuntimeConfig;
import io.wisetime.connector.config.RuntimeConfigKey;

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
    return Connector.builder()
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
    TAG_MODIFIER_WORK_CODE_MAPPING("TAG_MODIFIER_WORK_CODE_MAPPING"),
    DEFAULT_MODIFIER("DEFAULT_MODIFIER"),

    //optional
    TAG_UPSERT_PATH("TAG_UPSERT_PATH"),
    TAG_UPSERT_BATCH_SIZE("TAG_UPSERT_BATCH_SIZE"),
    TIMEZONE("TIMEZONE"),
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
    private static final Logger log = LoggerFactory.getLogger(PatriciaDbModule.class);

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

      log.info("Connecting to Patricia database at URL: {}, Username: {}", hikariConfig.getJdbcUrl(),
          hikariConfig.getUsername());

      bind(HikariDataSource.class).toInstance(new HikariDataSource(hikariConfig));
    }
  }
}

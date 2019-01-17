package io.wisetime.connector.patricia;

import com.google.inject.Inject;

import java.util.function.Supplier;

import io.wisetime.connector.config.RuntimeConfig;
import io.wisetime.connector.template.TemplateFormatter;
import io.wisetime.connector.template.TemplateFormatterConfig;

/**
 * @author thomas.haines@practiceinsight.io
 */
class PatriciaFormatterConfigurator {

  private final TemplateFormatter timeRegistrationTemplate;
  private final TemplateFormatter chargeTemplate;

  @Inject
  PatriciaFormatterConfigurator() {
    boolean includeTimeDuration =
        RuntimeConfig.getString(ConnectorLauncher.PatriciaConnectorConfigKey.INCLUDE_DURATIONS_IN_INVOICE_COMMENT)
            .map(Boolean::parseBoolean)
            .orElse(false);

    if (includeTimeDuration) {
      timeRegistrationTemplate = createTemplateFormatter(() -> "classpath:patricia-with-duration_time-registration.ftl");
      chargeTemplate = createTemplateFormatter(() -> "classpath:patricia-with-duration_charge.ftl");
    } else {
      timeRegistrationTemplate = createTemplateFormatter(() -> "classpath:patricia-no-duration_time-registration.ftl");
      chargeTemplate = createTemplateFormatter(() -> "classpath:patricia-no-duration_charge.ftl");
    }
  }

  private TemplateFormatter createTemplateFormatter(Supplier<String> getTemplatePath) {
    return new TemplateFormatter(TemplateFormatterConfig.builder()
        .withTemplatePath(getTemplatePath.get())
        .build());
  }


  TemplateFormatter getTimeRegistrationTemplate() {
    return timeRegistrationTemplate;
  }

  TemplateFormatter getChargeTemplate() {
    return chargeTemplate;
  }

}

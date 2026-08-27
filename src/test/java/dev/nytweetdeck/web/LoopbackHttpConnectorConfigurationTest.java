package dev.nytweetdeck.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;

class LoopbackHttpConnectorConfigurationTest {

    @Test
    void addsTheLegacyLoopbackHttpPortOnlyWhenLocalHttpsIsEnabled() {
        var disabledFactory = new TomcatServletWebServerFactory();
        new LoopbackHttpConnectorConfiguration(0).customize(disabledFactory);
        assertThat(disabledFactory.getAdditionalConnectors()).isEmpty();

        var enabledFactory = new TomcatServletWebServerFactory();
        new LoopbackHttpConnectorConfiguration(18080).customize(enabledFactory);

        assertThat(enabledFactory.getAdditionalConnectors()).singleElement().satisfies(connector -> {
            assertThat(connector.getPort()).isEqualTo(18080);
            assertThat(connector.getScheme()).isEqualTo("http");
            assertThat(connector.getProperty("address").toString()).isEqualTo("/127.0.0.1");
        });
    }
}

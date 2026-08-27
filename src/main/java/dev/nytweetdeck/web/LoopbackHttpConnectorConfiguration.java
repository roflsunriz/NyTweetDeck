package dev.nytweetdeck.web;

import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.stereotype.Component;

@Component
public class LoopbackHttpConnectorConfiguration
        implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

    private final int httpPort;

    public LoopbackHttpConnectorConfiguration(
            @Value("${nytweetdeck.http.port:0}") int httpPort) {
        this.httpPort = httpPort;
    }

    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        if (httpPort <= 0) {
            return;
        }
        var connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
        connector.setScheme("http");
        connector.setSecure(false);
        connector.setPort(httpPort);
        connector.setProperty("address", "127.0.0.1");
        factory.addAdditionalConnectors(connector);
    }
}

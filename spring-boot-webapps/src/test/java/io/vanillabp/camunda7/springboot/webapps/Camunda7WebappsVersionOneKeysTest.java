package io.vanillabp.camunda7.springboot.webapps;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.web.client.RestClient;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A key of Camunda's Spring Boot starter ends the start, except where something really
 * reads it. This module hands <code>camunda.bpm.webapp.*</code> to Camunda's web
 * applications and reads <code>camunda.bpm.enabled</code> itself. So with this module both
 * may stay in the configuration. The adapter finds this module by the name of
 * {@link Camunda7WebappsAutoConfiguration}. This test goes red if that class is renamed, because
 * then the key below ends the start.
 */
@ExtendWith(SuppressOutputExtension.class)
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.datasource.url=jdbc:h2:mem:c7-webapps-v1-keys;DB_CLOSE_DELAY=-1", "camunda.bpm.enabled=false", "camunda.bpm.webapp.application-path=/engine-ui"
    })
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
public class Camunda7WebappsVersionOneKeysTest {

  @LocalServerPort
  private int port;

  @Test
  @DisplayName("A key the web applications read does not end the start, and it takes effect")
  public void aKeyTheWebappsReadIsKept() {

    final var entry = RestClient
        .builder()
        .baseUrl("http://localhost:%d".formatted(port))
        .build()
        .get()
        .uri("/engine-ui")
        .retrieve()
        .toBodilessEntity();

    assertTrue(entry.getStatusCode().is3xxRedirection(), () -> String.valueOf(entry.getStatusCode()));
    assertTrue(
        entry
            .getHeaders()
            .getFirst(HttpHeaders.LOCATION)
            .contains("/engine-ui/app/"),
        () -> entry
            .getHeaders()
            .getFirst(HttpHeaders.LOCATION));

  }

}

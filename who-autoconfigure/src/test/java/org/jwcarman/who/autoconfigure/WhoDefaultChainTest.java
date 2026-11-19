/*
 * Copyright © 2026 James Carman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jwcarman.who.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.config.Customizer.withDefaults;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.email.WhoEmailSender;
import org.jwcarman.who.spring.security.WhoSecurity;
import org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.health.autoconfigure.actuate.endpoint.HealthEndpointAutoConfiguration;
import org.springframework.boot.health.autoconfigure.contributor.HealthContributorAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.web.OAuth2ResourceServerWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * With no {@link SecurityFilterChain} of the application's own, who registers one that mounts every
 * sign-in method on the classpath, lets signed-out requests reach the public paths, and answers
 * every other signed-out request with {@code 401}.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class WhoDefaultChainTest {

  /** API keys only: the other sign-in methods are off, so no further configuration is needed. */
  private static WebApplicationContextRunner runner(Class<?>... boot) {
    Class<?>[] all = new Class<?>[boot.length + 2];
    all[0] = SecurityAutoConfiguration.class;
    all[1] = ServletWebSecurityAutoConfiguration.class;
    System.arraycopy(boot, 0, all, 2, boot.length);
    return new WebApplicationContextRunner()
        .withPropertyValues("who.migrations.enabled=false")
        .withConfiguration(WhoTestAutoConfigurations.who(all))
        .withUserConfiguration(WhoSecurityAllTest.Database.class)
        .withPropertyValues(
            "who.email-link.enabled=false", "who.passkey.enabled=false", "who.jwt.enabled=false");
  }

  private static MockMvc mvc(AssertableWebApplicationContext context) {
    return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  private static int status(AssertableWebApplicationContext context, String path) throws Exception {
    return mvc(context).perform(get(path)).andReturn().getResponse().getStatus();
  }

  @Nested
  class Without_an_application_chain {

    @Test
    void registers_whos_chain_when_the_application_has_none() {
      runner()
          .run(
              context -> {
                assertThat(context.getBeansOfType(SecurityFilterChain.class))
                    .containsOnlyKeys("whoSecurityFilterChain");
                MvcResult result = mvc(context).perform(get("/anything")).andReturn();
                assertThat(result.getResponse().getStatus()).isEqualTo(401);
                assertThat(result.getResponse().getHeader("Location")).isNull();
              });
    }

    @Test
    void the_error_page_is_public() {
      runner().run(context -> assertThat(status(context, "/error")).isNotEqualTo(401));
    }

    @Test
    void configured_public_paths_are_public() {
      runner()
          .withPropertyValues("who.security.public-paths=/signin,/assets/**")
          .run(
              context -> {
                assertThat(status(context, "/signin")).isNotEqualTo(401);
                assertThat(status(context, "/assets/app.js")).isNotEqualTo(401);
                assertThat(status(context, "/private")).isEqualTo(401);
              });
    }
  }

  /**
   * CSRF guards a session against forged requests. Only a browser sign-in method gives a request a
   * session, so without one the default chain has nothing for CSRF to guard.
   */
  @Nested
  class Csrf {

    private int postStatus(AssertableWebApplicationContext context, String path) throws Exception {
      return mvc(context).perform(post(path)).andReturn().getResponse().getStatus();
    }

    @Test
    void without_a_browser_method_a_signed_out_post_to_a_public_path_needs_no_csrf_token() {
      runner()
          .withPropertyValues("who.security.public-paths=/signup")
          .run(context -> assertThat(postStatus(context, "/signup")).isNotEqualTo(403));
    }

    /**
     * Something other than who, such as a {@code Customizer<HttpSecurity>} bean adding a login, may
     * still give requests a session; a request that brings one keeps its CSRF check.
     */
    @Test
    void without_a_browser_method_a_post_that_brings_a_session_needs_a_csrf_token() {
      runner()
          .withPropertyValues("who.security.public-paths=/signup")
          .run(
              context ->
                  assertThat(
                          mvc(context)
                              .perform(
                                  post("/signup")
                                      .with(
                                          request -> {
                                            request.setRequestedSessionId("a-session");
                                            return request;
                                          }))
                              .andReturn()
                              .getResponse()
                              .getStatus())
                      .isEqualTo(403));
    }

    @Test
    void with_a_browser_method_a_signed_out_post_without_a_csrf_token_is_forbidden() {
      runner()
          .withPropertyValues(
              "who.security.public-paths=/signup",
              "who.passkey.enabled=true",
              "who.passkey.rp-id=localhost",
              "who.passkey.allowed-origins=http://localhost")
          .run(context -> assertThat(postStatus(context, "/signup")).isEqualTo(403));
    }
  }

  @Nested
  class With_boot_and_actuator_security {

    private WebApplicationContextRunner actuator() {
      return runner(
              OAuth2ResourceServerAutoConfiguration.class,
              OAuth2ResourceServerWebSecurityAutoConfiguration.class,
              EndpointAutoConfiguration.class,
              WebEndpointAutoConfiguration.class,
              HealthContributorAutoConfiguration.class,
              HealthEndpointAutoConfiguration.class,
              ManagementWebSecurityAutoConfiguration.class)
          .withUserConfiguration(Decoder.class)
          .withPropertyValues("who.jwt.enabled=true");
    }

    @Test
    void who_replaces_boots_and_actuators_default_chains() {
      actuator()
          .run(
              context ->
                  assertThat(context.getBeansOfType(SecurityFilterChain.class))
                      .containsOnlyKeys("whoSecurityFilterChain"));
    }
  }

  /** A running application with Actuator, configured only by its classpath. */
  @Nested
  @SpringBootTest(
      classes = ActuatorApp.class,
      webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
      properties = {
        "who.email-link.enabled=false",
        "who.passkey.enabled=false",
        "who.jwt.enabled=false",
        "spring.session.jdbc.initialize-schema=always"
      })
  class In_a_running_application_with_actuator {

    @LocalServerPort private int port;

    private int status(String path) throws Exception {
      return HttpClient.newHttpClient()
          .send(
              HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
              HttpResponse.BodyHandlers.discarding())
          .statusCode();
    }

    @Test
    void the_health_endpoint_stays_public() throws Exception {
      assertThat(status("/actuator/health")).isEqualTo(200);
    }

    @Test
    void other_actuator_endpoints_need_sign_in() throws Exception {
      assertThat(status("/actuator")).isEqualTo(401);
    }

    @Test
    void a_signed_out_request_gets_401_not_a_login_page() throws Exception {
      assertThat(status("/anything")).isEqualTo(401);
    }
  }

  @Nested
  class With_an_application_chain {

    @Test
    void an_application_chain_disables_the_default_and_is_left_alone() {
      runner()
          .withUserConfiguration(AppChain.class)
          .run(
              context -> {
                assertThat(context.getBeansOfType(SecurityFilterChain.class))
                    .containsOnlyKeys("appChain");
                assertThat(status(context, "/app/open")).isNotEqualTo(401);
                assertThat(status(context, "/error")).isEqualTo(401);
              });
    }

    @Test
    void all_keeps_csrf_off_in_a_chain_that_turned_it_off() {
      runner()
          .withUserConfiguration(NoCsrfChain.class)
          .run(
              context ->
                  assertThat(
                          mvc(context)
                              .perform(post("/app/hook"))
                              .andReturn()
                              .getResponse()
                              .getStatus())
                      .isEqualTo(401));
    }

    @Test
    void all_with_a_browser_method_fails_startup_in_a_chain_without_csrf() {
      runner()
          .withUserConfiguration(NoCsrfChain.class)
          .withPropertyValues(
              "who.passkey.enabled=true",
              "who.passkey.rp-id=localhost",
              "who.passkey.allowed-origins=http://localhost")
          .run(
              context ->
                  assertThat(context.getStartupFailure())
                      .isNotNull()
                      .rootCause()
                      .hasMessageContaining("CSRF"));
    }

    /**
     * Spring's resource server takes any {@code AuthenticationConverter} bean in the context when
     * the chain names none, so who must not publish one.
     */
    @Test
    void an_application_resource_server_beside_email_links_reads_bearer_tokens() {
      runner()
          .withUserConfiguration(Decoder.class, Mail.class, ResourceServerChain.class)
          .withPropertyValues(
              "who.email-link.enabled=true",
              "who.email-link.link-base-url=https://app.example.com/signin",
              "who.email-link.code-secret=test-code-secret-0123456789-abcdefghij")
          .run(
              context ->
                  assertThat(
                          mvc(context)
                              .perform(get("/app/private").header("Authorization", "Bearer good"))
                              .andReturn()
                              .getResponse()
                              .getStatus())
                      .isNotEqualTo(401));
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  @Import(WhoSecurityAllTest.Database.class)
  static class ActuatorApp {}

  @Configuration(proxyBeanMethods = false)
  static class Decoder {
    @Bean
    JwtDecoder jwtDecoder() {
      return token -> {
        Instant now = Instant.now();
        return new Jwt(token, now, now.plusSeconds(60), Map.of("alg", "none"), Map.of("sub", "x"));
      };
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class Mail {
    @Bean
    WhoEmailSender whoEmailSender() {
      return message -> {};
    }

    @Bean
    Executor whoEmailLinkExecutor() {
      return Runnable::run;
    }
  }

  /** An API-only chain that turns CSRF off itself. */
  @Configuration(proxyBeanMethods = false)
  static class NoCsrfChain {
    @Bean
    SecurityFilterChain noCsrfChain(HttpSecurity http) {
      http.csrf(AbstractHttpConfigurer::disable)
          .with(WhoSecurity.all(), withDefaults())
          .authorizeHttpRequests(a -> a.anyRequest().authenticated());
      return http.build();
    }
  }

  /** A resource server the application writes itself, naming no authentication converter. */
  @Configuration(proxyBeanMethods = false)
  static class ResourceServerChain {
    @Bean
    SecurityFilterChain resourceServerChain(HttpSecurity http) {
      http.authorizeHttpRequests(a -> a.anyRequest().authenticated())
          .oauth2ResourceServer(
              rs -> rs.jwt(jwt -> jwt.jwtAuthenticationConverter(JwtAuthenticationToken::new)));
      return http.build();
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class AppChain {
    @Bean
    SecurityFilterChain appChain(HttpSecurity http) {
      http.with(WhoSecurity.all(), withDefaults())
          .authorizeHttpRequests(
              a -> a.requestMatchers("/app/open").permitAll().anyRequest().authenticated());
      return http.build();
    }
  }
}

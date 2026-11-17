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
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jwcarman.who.spring.security.WhoPublicPathContributor;
import org.jwcarman.who.spring.security.WhoPublicPaths;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.annotation.Order;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** A library adds public paths with a {@link WhoPublicPathContributor} bean. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@ExtendWith(OutputCaptureExtension.class)
class WhoPublicPathContributorTest {

  private static WebApplicationContextRunner runner() {
    return new WebApplicationContextRunner()
        .withConfiguration(
            WhoTestAutoConfigurations.who(
                SecurityAutoConfiguration.class, ServletWebSecurityAutoConfiguration.class))
        .withUserConfiguration(WhoSecurityAllTest.Database.class)
        .withPropertyValues(
            "who.email-link.enabled=false", "who.passkey.enabled=false", "who.jwt.enabled=false");
  }

  private static WhoPublicPathContributor contributing(String... paths) {
    return () -> Arrays.asList(paths);
  }

  private static int status(AssertableWebApplicationContext context, String path) throws Exception {
    MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    return mvc.perform(get(path)).andReturn().getResponse().getStatus();
  }

  @Test
  void a_contributed_path_is_open_to_signed_out_requests() {
    runner()
        .withBean(
            "shell",
            WhoPublicPathContributor.class,
            () -> contributing("/index.html", "/assets/**"))
        .run(
            context -> {
              assertThat(status(context, "/index.html")).isNotEqualTo(401);
              assertThat(status(context, "/assets/app.js")).isNotEqualTo(401);
              assertThat(status(context, "/private")).isEqualTo(401);
            });
  }

  @Test
  void contributors_compose_with_the_property_and_each_other() {
    runner()
        .withPropertyValues("who.security.public-paths=/signin")
        .withBean("first", WhoPublicPathContributor.class, () -> contributing("/index.html"))
        .withBean(
            "second", WhoPublicPathContributor.class, () -> contributing("/assets/**", "/signin"))
        .run(
            context ->
                assertThat(context.getBean(WhoPublicPaths.class).patterns())
                    .containsExactly(
                        "/error", "/who/sign-in", "/signin", "/index.html", "/assets/**"));
  }

  @Test
  void contributed_paths_are_app_root_patterns_not_under_the_base_path() {
    runner()
        .withPropertyValues("who.base-path=/auth")
        .withBean("shell", WhoPublicPathContributor.class, () -> contributing("/index.html"))
        .run(
            context ->
                assertThat(context.getBean(WhoPublicPaths.class).patterns())
                    .containsExactly("/error", "/auth/sign-in", "/index.html"));
  }

  @Test
  void a_blank_contributed_path_fails_startup() {
    runner()
        .withBean("shell", WhoPublicPathContributor.class, () -> contributing("/index.html", " "))
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .isInstanceOf(WhoConfigurationException.class)
                    .hasMessageContaining("shell"));
  }

  @Test
  void a_null_contributed_path_fails_startup() {
    runner()
        .withBean("shell", WhoPublicPathContributor.class, () -> contributing("/a", null))
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .isInstanceOf(WhoConfigurationException.class)
                    .hasMessageContaining("shell"));
  }

  @Test
  void a_null_collection_fails_startup() {
    WhoPublicPathContributor none = () -> null;
    runner()
        .withBean("shell", WhoPublicPathContributor.class, () -> none)
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .isInstanceOf(WhoConfigurationException.class)
                    .hasMessageContaining("shell"));
  }

  @Test
  void contributed_paths_are_logged_with_their_source(CapturedOutput output) {
    runner()
        .withPropertyValues("who.security.public-paths=/from-the-property")
        .withBean("shell", WhoPublicPathContributor.class, () -> contributing("/index.html"))
        .run(
            context -> {
              assertThat(output).contains("public path /index.html contributed by bean 'shell'");
              assertThat(output).doesNotContain("/from-the-property");
            });
  }

  @Test
  void ordering_on_contributors_decides_the_order_of_patterns() {
    runner()
        .withBean("late", Late.class, Late::new)
        .withBean("early", Early.class, Early::new)
        .run(
            context ->
                assertThat(context.getBean(WhoPublicPaths.class).patterns())
                    .containsExactly("/error", "/who/sign-in", "/early", "/late"));
  }

  @Order(2)
  static class Late implements WhoPublicPathContributor {
    @Override
    public Collection<String> publicPaths() {
      return List.of("/late");
    }
  }

  @Order(1)
  static class Early implements WhoPublicPathContributor {
    @Override
    public Collection<String> publicPaths() {
      return List.of("/early");
    }
  }

  @Test
  void no_contributors_changes_nothing() {
    runner()
        .run(
            context ->
                assertThat(context.getBean(WhoPublicPaths.class).patterns())
                    .containsExactly("/error", "/who/sign-in"));
  }
}

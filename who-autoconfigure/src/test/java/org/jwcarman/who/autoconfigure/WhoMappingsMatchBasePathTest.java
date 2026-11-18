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

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.spring.security.WhoPaths;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class WhoMappingsMatchBasePathTest {

  private final WebApplicationContextRunner runner =
      new WebApplicationContextRunner()
          .withConfiguration(
              WhoTestAutoConfigurations.who(
                  WebMvcAutoConfiguration.class, DispatcherServletAutoConfiguration.class))
          .withUserConfiguration(WhoAutoConfigurationTest.TestDataSourceConfig.class)
          .withPropertyValues(
              "who.email-link.enabled=false", "who.passkey.enabled=false", "who.jwt.enabled=false");

  @Test
  void who_starts_at_its_base_path() {
    runner
        .withPropertyValues("who.base-path=/auth")
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  void a_path_prefix_on_controllers_fails_startup() {
    runner
        .withUserConfiguration(ApiPrefix.class)
        .run(
            context ->
                assertThat(context)
                    .getFailure()
                    .hasMessageContaining("answers at /api/who/")
                    .hasMessageContaining("who.base-path"));
  }

  @Test
  void a_servlet_path_fails_startup() {
    runner
        .withPropertyValues("spring.mvc.servlet.path=/api")
        .run(
            context ->
                assertThat(context).getFailure().hasMessageContaining("spring.mvc.servlet.path"));
  }

  @Test
  void a_whopaths_bean_that_disagrees_with_the_property_fails_startup() {
    runner
        .withUserConfiguration(OwnPaths.class)
        .run(context -> assertThat(context).getFailure().hasMessageContaining(", not /auth/"));
  }

  @Configuration(proxyBeanMethods = false)
  static class ApiPrefix implements WebMvcConfigurer {
    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
      configurer.addPathPrefix("/api", type -> type.isAnnotationPresent(RestController.class));
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class OwnPaths {
    @Bean
    WhoPaths whoPaths() {
      return new WhoPaths("/auth");
    }
  }
}

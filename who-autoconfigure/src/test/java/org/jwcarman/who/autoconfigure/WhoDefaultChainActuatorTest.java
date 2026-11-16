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

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The default chain opens the health endpoint only when actuator is in the application; {@code
 * WhoDefaultChainTest} shows it open when it is.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class WhoDefaultChainActuatorTest {

  private static WebApplicationContextRunner runner() {
    return new WebApplicationContextRunner()
        .withConfiguration(
            WhoTestAutoConfigurations.who(
                SecurityAutoConfiguration.class, ServletWebSecurityAutoConfiguration.class))
        .withUserConfiguration(WhoSecurityAllTest.Database.class)
        .withPropertyValues(
            "who.email-link.enabled=false", "who.passkey.enabled=false", "who.jwt.enabled=false");
  }

  private static int health(WebApplicationContext context) throws Exception {
    return MockMvcBuilders.webAppContextSetup(context)
        .apply(springSecurity())
        .build()
        .perform(get("/actuator/health"))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  @Test
  void without_actuator_nothing_is_opened() {
    runner()
        .withClassLoader(new FilteredClassLoader(WebEndpointAutoConfiguration.class))
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(health(context)).isEqualTo(401);
            });
  }
}

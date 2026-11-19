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

import java.util.ArrayList;
import java.util.List;

import org.jwcarman.migratory.autoconfigure.MigratoryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;

/**
 * Every who autoconfiguration, plus the Spring Boot security autoconfigurations that come with
 * who-spring-security, as the application context of a real app would load them.
 */
final class WhoTestAutoConfigurations {

  private static final List<Class<?>> WHO =
      List.of(
          SecurityAutoConfiguration.class,
          ServletWebSecurityAutoConfiguration.class,
          WhoAutoConfiguration.class,
          MigratoryAutoConfiguration.class,
          WhoSpringSecurityAutoConfiguration.class,
          WhoEnrollmentAutoConfiguration.class,
          WhoFederatedAutoConfiguration.class,
          WhoJwtAutoConfiguration.class,
          WhoApiKeyAutoConfiguration.class,
          WhoEmailAutoConfiguration.class,
          WhoEmailLinkAutoConfiguration.class,
          WhoPasskeyAutoConfiguration.class,
          WhoOidcAutoConfiguration.class,
          WhoAdminAutoConfiguration.class,
          WhoWebSecurityAutoConfiguration.class);

  private WhoTestAutoConfigurations() {}

  /**
   * who's autoconfigurations plus the given ones.
   *
   * @param others further autoconfigurations, such as Spring Boot's data source
   * @return all of them, in Spring Boot's order
   */
  static AutoConfigurations who(Class<?>... others) {
    List<Class<?>> all = new ArrayList<>(List.of(others));
    WHO.stream().filter(c -> !all.contains(c)).forEach(all::add);
    return AutoConfigurations.of(all.toArray(Class<?>[]::new));
  }
}

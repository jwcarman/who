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
package org.jwcarman.who.examples.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * A team task board as a stateless JSON API: people call it with access tokens from an
 * authorization server, machines with who API keys. It has no security configuration of its own:
 * who's default chain does all of it.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ApiApplication {

  /** Creates the application; Spring Boot calls it. */
  public ApiApplication() {
    // nothing to set up: Spring Boot configures the application
  }

  /**
   * Starts the example.
   *
   * @param args the command line
   */
  public static void main(String[] args) {
    SpringApplication.run(ApiApplication.class, args);
  }
}

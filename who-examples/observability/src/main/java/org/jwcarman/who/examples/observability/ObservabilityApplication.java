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
package org.jwcarman.who.examples.observability;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** who's meters, traces and logs in Grafana's LGTM stack. */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class ObservabilityApplication {

  /** Creates the application; Spring Boot calls it. */
  public ObservabilityApplication() {
    // nothing to set up: Spring Boot configures the application
  }

  /**
   * Starts the example.
   *
   * @param args the command line
   */
  public static void main(String[] args) {
    SpringApplication.run(ObservabilityApplication.class, args);
  }
}

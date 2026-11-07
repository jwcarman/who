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
package org.jwcarman.who.example;

import org.jwcarman.who.emaillink.EmailLinkSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

@SpringBootApplication
@EnableMethodSecurity
public class WhoExampleApplication {

  private static final Logger log = LoggerFactory.getLogger(WhoExampleApplication.class);

  public static void main(String[] args) {
    SpringApplication.run(WhoExampleApplication.class, args);
  }

  /**
   * The example has no mail server, so sign-in links are not delivered; a real application uses
   * Spring Boot's {@code spring.mail.*} properties instead. The link itself is never logged.
   */
  @Bean
  EmailLinkSender emailLinkSender() {
    return (address, link) -> log.info("Sign-in link requested for {} (not delivered)", address);
  }
}

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

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Counts who's events as Micrometer meters when the application has a {@link MeterRegistry} (for
 * example, with Spring Boot Actuator). See {@link WhoMetrics} and {@link WhoEmailLinkMetrics}.
 */
@AutoConfiguration(
    afterName = {
      "org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration",
      "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration",
      "org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration"
    })
@ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
@ConditionalOnBean(MeterRegistry.class)
public class WhoMetricsAutoConfiguration {

  /**
   * Counts who's own events.
   *
   * @param registry the application's meter registry
   * @return the listener
   */
  @Bean
  @ConditionalOnMissingBean
  public WhoMetrics whoMetrics(MeterRegistry registry) {
    return new WhoMetrics(registry);
  }

  /** Counts sign-in emails when who-email-link is on the classpath. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "org.jwcarman.who.emaillink.SignInEmailRequested")
  static class EmailLinkMetricsConfiguration {

    /**
     * Counts sign-in email requests.
     *
     * @param registry the application's meter registry
     * @return the listener
     */
    @Bean
    @ConditionalOnMissingBean
    public WhoEmailLinkMetrics whoEmailLinkMetrics(MeterRegistry registry) {
      return new WhoEmailLinkMetrics(registry);
    }
  }
}

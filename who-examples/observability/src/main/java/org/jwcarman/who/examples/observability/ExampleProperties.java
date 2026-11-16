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

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The example's own settings.
 *
 * @param traffic the traffic generator
 * @param mailpitUrl Mailpit's web address, where the generator reads its sign-in codes
 */
@ConfigurationProperties("example")
record ExampleProperties(
    @DefaultValue Traffic traffic, @DefaultValue("http://localhost:8025") URI mailpitUrl) {

  /**
   * The traffic generator.
   *
   * @param enabled whether it runs; {@code example.traffic.enabled=false} turns it off
   * @param interval how long it waits between requests
   */
  record Traffic(
      @DefaultValue("true") boolean enabled, @DefaultValue("1500ms") Duration interval) {}
}

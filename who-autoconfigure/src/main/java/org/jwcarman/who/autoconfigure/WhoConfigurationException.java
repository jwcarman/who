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

import static java.util.Objects.requireNonNull;

/**
 * Thrown at startup when a who module is on the classpath but a setting it cannot work without is
 * missing or unusable. Spring Boot reports it through {@link WhoConfigurationFailureAnalyzer}: what
 * is wrong, and what to set.
 */
public final class WhoConfigurationException extends IllegalStateException {

  private static final long serialVersionUID = 1L;

  /** The configuration property at fault. */
  private final String property;

  /** What is wrong with it. */
  private final String problem;

  /** What to set to fix it. */
  private final String action;

  /**
   * Creates the exception.
   *
   * @param property the configuration property at fault, such as {@code who.passkey.rp-id}
   * @param problem what is wrong with it, completing a sentence that starts with the property
   * @param action what to set to fix it
   */
  public WhoConfigurationException(String property, String problem, String action) {
    super(
        requireNonNull(property, "property")
            + " "
            + requireNonNull(problem, "problem")
            + ". "
            + requireNonNull(action, "action"));
    this.property = property;
    this.problem = problem;
    this.action = action;
  }

  /**
   * Returns the property at fault.
   *
   * @return the property name
   */
  public String property() {
    return property;
  }

  /**
   * Returns what is wrong with the property.
   *
   * @return the problem
   */
  public String problem() {
    return problem;
  }

  /**
   * Returns what to set to fix it.
   *
   * @return the action
   */
  public String action() {
    return action;
  }
}

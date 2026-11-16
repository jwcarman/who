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
package org.jwcarman.who.oidc;

/** A client registration who cannot sign people in with safely. The application fails to start. */
public final class InvalidRegistrationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** The client registration. */
  private final String registrationId;

  /** The property to fix. */
  private final String property;

  /** What is wrong, phrased to follow the property name. */
  private final String problem;

  /**
   * Creates the exception.
   *
   * @param registrationId the client registration
   * @param property the property to fix
   * @param problem what is wrong, phrased to follow the property name
   */
  public InvalidRegistrationException(String registrationId, String property, String problem) {
    super(property + " " + problem + " (client registration '" + registrationId + "')");
    this.registrationId = registrationId;
    this.property = property;
    this.problem = problem;
  }

  /**
   * The client registration.
   *
   * @return its id
   */
  public String registrationId() {
    return registrationId;
  }

  /**
   * The property to fix.
   *
   * @return the property name
   */
  public String property() {
    return property;
  }

  /**
   * What is wrong.
   *
   * @return the problem, phrased to follow the property name
   */
  public String problem() {
    return problem;
  }
}

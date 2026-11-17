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
package org.jwcarman.who.spring.security;

/** What became of a human check. */
public enum HumanCheckOutcome {
  /** The check passed the request, which went on to the endpoint. */
  PASSED,
  /** The check refused the request: no token, or a token the check did not accept. */
  REFUSED,
  /** The check threw, so the request was refused. */
  ERROR
}

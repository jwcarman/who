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

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Reports a {@link WhoConfigurationException} the way Spring Boot reports its own configuration
 * failures, so the startup output says which property is wrong and what to set.
 */
public final class WhoConfigurationFailureAnalyzer
    extends AbstractFailureAnalyzer<WhoConfigurationException> {

  @Override
  protected FailureAnalysis analyze(Throwable rootFailure, WhoConfigurationException cause) {
    return new FailureAnalysis(
        "who cannot start: " + cause.property() + " " + cause.problem() + ".",
        cause.action(),
        cause);
  }
}

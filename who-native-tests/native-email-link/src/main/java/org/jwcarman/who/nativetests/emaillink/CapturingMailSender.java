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
package org.jwcarman.who.nativetests.emaillink;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.jwcarman.who.core.email.OutgoingEmail;
import org.jwcarman.who.core.email.WhoEmailSender;
import org.springframework.stereotype.Component;

/**
 * Keeps the text of the last email to each address in memory instead of delivering it, so the test
 * reads sign-in links and codes without a mail server. Every who email reaches it rendered, so the
 * native image renders who's built-in templates as an application would.
 */
@Component
class CapturingMailSender implements WhoEmailSender {

  private final Map<String, String> lastByAddress = new ConcurrentHashMap<>();

  @Override
  public void send(OutgoingEmail email) {
    lastByAddress.put(email.to(), email.text());
  }

  Optional<String> lastTo(String address) {
    return Optional.ofNullable(lastByAddress.get(address));
  }
}

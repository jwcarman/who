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
package org.jwcarman.who.emaillink;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class EmailLinkMessageTest {

  @Test
  void to_string_hides_the_link_and_the_code() {
    EmailLinkMessage message =
        new EmailLinkMessage(
            "a@b.c",
            "https://app.test/signin#token=SECRETTOKEN",
            "K7F2QM",
            Instant.parse("2026-11-13T10:00:00Z"));

    assertThat(message.toString())
        .doesNotContain("SECRETTOKEN", "K7F2QM", "https://")
        .contains("a@b.c", "link=****", "code=****");
  }
}

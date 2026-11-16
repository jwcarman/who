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

import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.jwcarman.who.spring.security.web.WhoErrorCode;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class EmailErrorCodesTest {

  static Stream<Arguments> codes() {
    return Stream.of(
        Arguments.of(new EmailAddressTakenException(), "email-address-taken"),
        Arguments.of(new EmailCredentialNotFoundException(), "email-address-not-found"),
        Arguments.of(new InvalidEmailAddressConfirmationException(), "invalid-confirmation"));
  }

  @ParameterizedTest
  @MethodSource("codes")
  void each_exception_who_hands_off_names_itself(Exception exception, String code) {
    assertThat(exception).isInstanceOf(WhoErrorCode.class);
    assertThat(((WhoErrorCode) exception).errorCode()).isEqualTo(code);
  }
}

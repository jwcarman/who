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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class LoginMethodNamesTest {

  static class NamelessStateless implements StatelessLoginMethod {
    @Override
    public void configure(HttpSecurity http) {
      // nothing to mount: only the name is under test
    }

    @Override
    public RequestMatcher carriesCredential() {
      return AnyRequestMatcher.INSTANCE;
    }
  }

  @Test
  void a_lambda_passwordless_method_is_named_by_its_class() {
    PasswordlessLoginMethod lambda = http -> {};

    assertThat(lambda.name()).isEqualTo(lambda.getClass().getSimpleName()).isNotBlank();
  }

  @Test
  void a_stateless_method_without_a_name_is_named_by_its_class() {
    assertThat(new NamelessStateless().name()).isEqualTo("NamelessStateless");
  }
}

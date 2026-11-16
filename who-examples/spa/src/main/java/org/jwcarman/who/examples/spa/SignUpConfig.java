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
package org.jwcarman.who.examples.spa;

import org.jwcarman.who.core.signup.ClosedSignUpPolicy;
import org.jwcarman.who.core.signup.SignUpDecision;
import org.jwcarman.who.core.signup.WhoSignUpPolicy;
import org.jwcarman.who.rbac.RbacService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Who may create an account by signing in with a provider who does not know yet. */
@Configuration(proxyBeanMethods = false)
class SignUpConfig {

  /**
   * Anyone with an account in the local Keycloak realm may sign up, and becomes a member. Every
   * other provider is closed: a Google or GitHub account signs in only after a member links it from
   * their profile.
   *
   * <p>Decide by who the provider is, or by a claim it vouches for (Google's {@code hd}, Entra's
   * {@code tid}), never by an email domain: a verified address outlives a person's employment.
   */
  @Bean
  WhoSignUpPolicy whoSignUpPolicy(RbacService rbacService) {
    ClosedSignUpPolicy closed = new ClosedSignUpPolicy();
    return request -> {
      if ("keycloak".equals(request.registrationId())) {
        return SignUpDecision.allow(
            identity -> rbacService.assignRoleByName(identity, TeamRole.MEMBER));
      }
      return closed.decide(request);
    };
  }
}

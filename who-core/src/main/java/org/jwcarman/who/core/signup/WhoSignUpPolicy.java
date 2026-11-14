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
package org.jwcarman.who.core.signup;

/**
 * Decides whether someone who signs in with a credential who does not know may create an identity.
 *
 * <p>who asks only after the person has confirmed that they want an account, and never because of
 * an email address alone. who's default policy, {@link ClosedSignUpPolicy}, refuses everyone; an
 * application opens sign-up by defining its own bean, which may delegate to the closed policy.
 * Allow-list an organization by a claim the provider vouches for, such as Google's {@code hd} or
 * Microsoft Entra's {@code tid}, never by email domain: a verified address outlives a person's
 * employment.
 */
@FunctionalInterface
public interface WhoSignUpPolicy {

  /**
   * Decides a sign-up.
   *
   * @param request who is asking, and how they proved it
   * @return {@link SignUpDecision#allow()} or {@link SignUpDecision#refuse()}
   */
  SignUpDecision decide(SignUpRequest request);
}

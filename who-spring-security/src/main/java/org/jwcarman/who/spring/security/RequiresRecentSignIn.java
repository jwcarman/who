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

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The caller must have signed in recently: within {@code who.security.recent-sign-in} (10 minutes
 * by default), or within {@link #within()}. Use it on actions a stolen session should not be able
 * to take, such as making an API key.
 *
 * <pre>{@code
 * @PostMapping("/keys")
 * @RequiresPermission("keys.write")
 * @RequiresRecentSignIn
 * public NewKey make(...) { ... }
 * }</pre>
 *
 * <ul>
 *   <li>A browser session that signed in too long ago gets {@code 401} {@code
 *       ReauthenticationRequiredException}: sign the person in again, then retry.
 *   <li>A JWT whose {@code auth_time} is too long ago gets {@code 401} with the RFC 9470 step-up
 *       challenge, {@code InsufficientUserAuthenticationException}.
 *   <li>A JWT without {@code auth_time}, and an API key, get {@code 403} {@code
 *       RecentSignInUnavailableException}: neither can ever be recent.
 * </ul>
 *
 * <p>On a type it covers every method; it works as a meta-annotation too. It is enforced by who's
 * method security ({@code who.method-security.enabled}), after any {@code @PreAuthorize} or {@link
 * RequiresPermission @RequiresPermission} check on the same method. For URL rules, use the {@link
 * RecentSignInAuthorizationManager} bean: {@code .access(recentSignIn)}.
 */
@Target({ElementType.METHOD, ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequiresRecentSignIn {

  /**
   * How recent the sign-in must be, as a duration such as {@code 5m} or {@code PT5M}; empty, the
   * default, means {@code who.security.recent-sign-in}.
   *
   * @return the window, or empty
   */
  String within() default "";
}

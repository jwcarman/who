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

import static org.jwcarman.who.core.observation.WhoObservations.tagValue;

import org.jwcarman.who.core.event.CredentialAdded;
import org.jwcarman.who.core.event.CredentialRevoked;
import org.jwcarman.who.core.event.SessionEnded;
import org.jwcarman.who.core.event.SignInFailed;
import org.jwcarman.who.core.event.SignInSucceeded;
import org.jwcarman.who.core.event.SignedOut;
import org.jwcarman.who.core.event.WhoEvent;
import org.springframework.context.event.EventListener;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Counts who's events as Micrometer meters: {@value #SIGN_INS}, {@value #SIGN_OUTS}, {@value
 * #SESSIONS_ENDED}, {@value #CREDENTIALS_ADDED} and {@value #CREDENTIALS_REVOKED}. Every tag value
 * comes from one of who's enums; no address or id is ever a tag. Other events are ignored.
 */
public final class WhoMetrics {

  /** Sign-in attempts, tagged {@code method}, {@code outcome} and {@code reason}. */
  public static final String SIGN_INS = "who.sign-ins";

  /** Sign-outs of who sessions. */
  public static final String SIGN_OUTS = "who.sign-outs";

  /** Sessions who ended, tagged {@code reason}. */
  public static final String SESSIONS_ENDED = "who.sessions.ended";

  /** Credentials added, tagged {@code kind}. */
  public static final String CREDENTIALS_ADDED = "who.credentials.added";

  /** Credentials revoked, tagged {@code kind}. */
  public static final String CREDENTIALS_REVOKED = "who.credentials.revoked";

  private static final String KIND = "kind";
  private static final String REASON = "reason";

  private final MeterRegistry registry;

  /**
   * Creates the listener.
   *
   * @param registry where the counters are registered
   */
  public WhoMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  /**
   * Counts one event.
   *
   * @param event the event who published
   */
  @EventListener
  public void on(WhoEvent event) {
    switch (event) {
      case SignInSucceeded succeeded -> signIn(tagValue(succeeded.method()), "success", "none");
      case SignInFailed failed ->
          signIn(tagValue(failed.method()), "failure", tagValue(failed.reason()));
      case SignedOut _ ->
          Counter.builder(SIGN_OUTS)
              .description("Sign-outs of who sessions")
              .register(registry)
              .increment();
      case SessionEnded ended ->
          Counter.builder(SESSIONS_ENDED)
              .description("Sessions who ended, by reason")
              .tag(REASON, tagValue(ended.reason()))
              .register(registry)
              .increment();
      case CredentialAdded added ->
          Counter.builder(CREDENTIALS_ADDED)
              .description("Credentials added, by kind")
              .tag(KIND, tagValue(added.kind()))
              .register(registry)
              .increment();
      case CredentialRevoked revoked ->
          Counter.builder(CREDENTIALS_REVOKED)
              .description("Credentials revoked, by kind")
              .tag(KIND, tagValue(revoked.kind()))
              .register(registry)
              .increment();
      default -> {
        // not counted
      }
    }
  }

  private void signIn(String method, String outcome, String reason) {
    Counter.builder(SIGN_INS)
        .description("Sign-in attempts by method, outcome and failure reason")
        .tag("method", method)
        .tag("outcome", outcome)
        .tag(REASON, reason)
        .register(registry)
        .increment();
  }
}

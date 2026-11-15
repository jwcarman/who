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
package org.jwcarman.who.oidc;

import static java.util.Objects.requireNonNull;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A person's request to link a provider account, carried on the authorization request from {@code
 * POST /who/oidc/link/{registrationId}} to the provider's callback. It lives in the request's
 * attributes, which stay on the server for that one round trip, so neither the provider nor a URL
 * can set it, and it cannot outlive the round trip.
 *
 * @param identityId the identity that asked, the only one the account may be linked to
 * @param startedAt when the link started, so it can expire
 */
record LinkIntent(UUID identityId, Instant startedAt) implements Serializable {

  private static final String IDENTITY = "org.jwcarman.who.oidc.link.identity";
  private static final String STARTED_AT = "org.jwcarman.who.oidc.link.started-at";

  LinkIntent {
    requireNonNull(identityId, "identityId must not be null");
    requireNonNull(startedAt, "startedAt must not be null");
  }

  /**
   * Writes the intent as attributes. Strings, so the session holds nothing it cannot serialize.
   *
   * @param attributes the authorization request's attributes
   */
  void writeTo(Map<String, Object> attributes) {
    attributes.put(IDENTITY, identityId.toString());
    attributes.put(STARTED_AT, startedAt.toString());
  }

  /**
   * Reads the intent from an authorization request's attributes.
   *
   * @param attributes the attributes
   * @return the intent, or empty for a plain sign-in
   */
  static Optional<LinkIntent> readFrom(Map<String, Object> attributes) {
    if (!(attributes.get(IDENTITY) instanceof String identity)
        || !(attributes.get(STARTED_AT) instanceof String startedAt)) {
      return Optional.empty();
    }
    try {
      return Optional.of(new LinkIntent(UUID.fromString(identity), Instant.parse(startedAt)));
    } catch (IllegalArgumentException | DateTimeParseException _) {
      return Optional.empty();
    }
  }

  /**
   * Whether the link was started too long ago to finish now.
   *
   * @param now when the provider called back
   * @param lifetime how long a started link lasts
   * @return whether it has expired
   */
  boolean expired(Instant now, Duration lifetime) {
    return now.isAfter(startedAt.plus(lifetime));
  }
}

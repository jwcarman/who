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
package org.jwcarman.who.core.service;

import static java.util.Objects.requireNonNull;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jwcarman.who.core.domain.SessionRecord;
import org.jwcarman.who.core.event.EndedSessionsPurged;
import org.jwcarman.who.core.event.SessionEndReason;
import org.jwcarman.who.core.event.SessionEnded;
import org.jwcarman.who.core.event.SignInMethod;
import org.jwcarman.who.core.event.WhoEventPublisher;
import org.jwcarman.who.core.repository.SessionRecordRepository;

/**
 * who's record of browser sessions: one per sign-in, so a person can see where they are signed in
 * and end a session from elsewhere, and an application can end an identity's sessions.
 *
 * <p>A session is live while it is not ended and was seen within the idle timeout, the same timeout
 * the servlet container or Spring Session uses, so a session the container dropped without saying
 * so stops counting on its own. This service trusts its caller: it ends whatever session it is
 * asked to, and the endpoints decide who may end which.
 */
public class WhoSessions {

  /** The longest {@code User-Agent} kept. */
  public static final int MAX_USER_AGENT_LENGTH = 512;

  private static final String UNKNOWN_BROWSER = "Unknown browser";

  private final SessionRecordRepository repository;
  private final WhoEventPublisher events;
  private final Clock clock;
  private final Duration idleTimeout;
  private final Duration lastSeenInterval;
  private final int purgeBatchSize;
  private final boolean autoPurge;

  /**
   * Creates the service.
   *
   * @param repository where sessions are kept
   * @param events receives {@link SessionEnded} and {@link EndedSessionsPurged}
   * @param clock the current time
   * @param idleTimeout how long a session lasts without a request
   * @param lastSeenInterval how often a busy session's last-seen time is written
   * @param purgeBatchSize the most dead sessions one purge deletes
   * @param autoPurge whether each sign-in purges one batch
   */
  public WhoSessions(
      SessionRecordRepository repository,
      WhoEventPublisher events,
      Clock clock,
      Duration idleTimeout,
      Duration lastSeenInterval,
      int purgeBatchSize,
      boolean autoPurge) {
    this.repository = requireNonNull(repository, "repository must not be null");
    this.events = requireNonNull(events, "events must not be null");
    this.clock = requireNonNull(clock, "clock must not be null");
    this.idleTimeout = positive(idleTimeout, "idleTimeout");
    this.lastSeenInterval = positive(lastSeenInterval, "lastSeenInterval");
    if (purgeBatchSize < 1) {
      throw new IllegalArgumentException("purgeBatchSize must be positive");
    }
    this.purgeBatchSize = purgeBatchSize;
    this.autoPurge = autoPurge;
  }

  private static Duration positive(Duration duration, String name) {
    requireNonNull(duration, name + " must not be null");
    if (duration.isNegative() || duration.isZero()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
    return duration;
  }

  /**
   * Records a sign-in made now.
   *
   * @param identityId the identity that signed in
   * @param credentialId the credential it signed in with
   * @param method how it signed in
   * @param userAgent the browser's {@code User-Agent}, or {@code null}; longer ones are cut to
   *     {@value #MAX_USER_AGENT_LENGTH} characters
   * @return the new session
   */
  public SessionRecord start(
      UUID identityId, UUID credentialId, SignInMethod method, String userAgent) {
    return start(identityId, credentialId, method, userAgent, clock.instant());
  }

  /**
   * Records a sign-in made at {@code signedInAt}, seen now. Each call purges one batch of dead
   * sessions first when auto-purge is on.
   *
   * @param identityId the identity that signed in
   * @param credentialId the credential it signed in with
   * @param method how it signed in
   * @param userAgent the browser's {@code User-Agent}, or {@code null}
   * @param signedInAt when it signed in
   * @return the new session
   */
  public SessionRecord start(
      UUID identityId,
      UUID credentialId,
      SignInMethod method,
      String userAgent,
      Instant signedInAt) {
    if (autoPurge) {
      purgeEndedSessions();
    }
    SessionRecord session =
        new SessionRecord(
            UUID.randomUUID(),
            identityId,
            credentialId,
            method,
            signedInAt,
            clock.instant(),
            trimmed(userAgent),
            null);
    repository.insert(session);
    return session;
  }

  private static String trimmed(String userAgent) {
    if (userAgent == null || userAgent.isBlank()) {
      return null;
    }
    return userAgent.length() > MAX_USER_AGENT_LENGTH
        ? userAgent.substring(0, MAX_USER_AGENT_LENGTH)
        : userAgent;
  }

  /**
   * Finds a session that is still live: not ended, and seen within the idle timeout.
   *
   * @param sessionId the session's id
   * @return the session, or empty when it is unknown, ended or idle
   */
  public Optional<SessionRecord> findLive(UUID sessionId) {
    Instant seenSince = clock.instant().minus(idleTimeout);
    return repository
        .findById(sessionId)
        .filter(s -> s.endedAt() == null && !s.lastSeenAt().isBefore(seenSince));
  }

  /**
   * Records that a session made a request, writing at most once per last-seen interval.
   *
   * @param session the session, as found by {@link #findLive(UUID)}
   */
  public void seen(SessionRecord session) {
    Instant now = clock.instant();
    Instant notSince = now.minus(lastSeenInterval);
    if (session.lastSeenAt().isBefore(notSince)) {
      repository.touch(session.id(), now, notSince);
    }
  }

  /**
   * Lists an identity's live sessions, most recently signed in first.
   *
   * @param identityId the identity
   * @return the sessions
   */
  public List<SessionRecord> list(UUID identityId) {
    return repository.findLiveByIdentity(identityId, clock.instant().minus(idleTimeout));
  }

  /**
   * Ends a live session; its next request is signed out. Publishes {@link SessionEnded} with {@link
   * SessionEndReason#REVOKED}.
   *
   * @param sessionId the session's id
   * @return whether a live session was ended
   */
  public boolean end(UUID sessionId) {
    return findLive(sessionId).map(this::end).orElse(false);
  }

  private boolean end(SessionRecord session) {
    Instant now = clock.instant();
    if (!repository.end(session.id(), now)) {
      return false;
    }
    events.publish(
        new SessionEnded(
            now, session.identityId(), session.credentialId(), SessionEndReason.REVOKED));
    return true;
  }

  /**
   * Ends every live session of an identity, for instance after it reports a lost device.
   *
   * @param identityId the identity
   * @return how many were ended
   */
  public int endAll(UUID identityId) {
    return (int) list(identityId).stream().filter(this::end).count();
  }

  /**
   * Ends every live session of an identity but one, usually the one asking.
   *
   * @param identityId the identity
   * @param keepSessionId the session to keep
   * @return how many were ended
   */
  public int endAllExcept(UUID identityId, UUID keepSessionId) {
    return (int)
        list(identityId).stream()
            .filter(s -> !s.id().equals(keepSessionId))
            .filter(this::end)
            .count();
  }

  /**
   * Ends a session that signed out or expired. Publishes nothing: sign-out has its own event.
   *
   * @param sessionId the session's id
   */
  public void close(UUID sessionId) {
    repository.end(sessionId, clock.instant());
  }

  /**
   * Deletes at most one batch of dead sessions: ended ones, and ones idle past the timeout. Call it
   * again for more; it never tries to delete them all at once.
   *
   * @return how many were deleted
   */
  public int purgeEndedSessions() {
    Instant now = clock.instant();
    int purged = repository.deleteDead(now.minus(idleTimeout), purgeBatchSize);
    if (purged > 0) {
      events.publish(new EndedSessionsPurged(now, purged));
    }
    return purged;
  }

  /**
   * A short description of a browser, such as {@code "Firefox on macOS"}, from its {@code
   * User-Agent}. It reads only the browser and system families, and says so when it cannot tell.
   *
   * @param userAgent the {@code User-Agent}, or {@code null}
   * @return the label
   */
  public static String deviceLabel(String userAgent) {
    if (userAgent == null || userAgent.isBlank()) {
      return UNKNOWN_BROWSER;
    }
    return browser(userAgent) + " on " + system(userAgent);
  }

  // Edge and Opera also say Chrome, and Chrome also says Safari: check the specific ones first
  private static String browser(String ua) {
    if (ua.contains("Edg/")) {
      return "Edge";
    }
    if (ua.contains("OPR/") || ua.contains("Opera")) {
      return "Opera";
    }
    if (ua.contains("Firefox/") || ua.contains("FxiOS")) {
      return "Firefox";
    }
    if (ua.contains("Chrome/") || ua.contains("CriOS")) {
      return "Chrome";
    }
    if (ua.contains("Safari/")) {
      return "Safari";
    }
    return UNKNOWN_BROWSER;
  }

  // ChromeOS and Android also say Linux, and iOS says "like Mac OS X": check them first
  private static String system(String ua) {
    if (ua.contains("CrOS")) {
      return "ChromeOS";
    }
    if (ua.contains("Android")) {
      return "Android";
    }
    if (ua.contains("iPhone") || ua.contains("iPad")) {
      return "iOS";
    }
    if (ua.contains("Windows")) {
      return "Windows";
    }
    if (ua.contains("Macintosh") || ua.contains("Mac OS X")) {
      return "macOS";
    }
    if (ua.contains("Linux")) {
      return "Linux";
    }
    return "unknown system";
  }
}

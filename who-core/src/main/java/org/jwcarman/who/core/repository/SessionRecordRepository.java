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
package org.jwcarman.who.core.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jwcarman.who.core.domain.SessionRecord;

/** Stores who's record of browser sessions. */
public interface SessionRecordRepository {

  /**
   * Saves a new session.
   *
   * @param session the session
   */
  void insert(SessionRecord session);

  /**
   * Finds a session, ended or not.
   *
   * @param id the session's id
   * @return the session, or empty
   */
  Optional<SessionRecord> findById(UUID id);

  /**
   * Finds an identity's sessions that are not ended and were seen at or after {@code seenSince},
   * most recently signed in first.
   *
   * @param identityId the identity
   * @param seenSince the oldest last-seen time that still counts as live
   * @return the sessions
   */
  List<SessionRecord> findLiveByIdentity(UUID identityId, Instant seenSince);

  /**
   * Records that a session made a request, unless it is ended or was already seen at or after
   * {@code notSince}.
   *
   * @param id the session's id
   * @param now the time to record
   * @param notSince write only when the session was last seen before this
   * @return whether it was written
   */
  boolean touch(UUID id, Instant now, Instant notSince);

  /**
   * Ends a session that is not already ended.
   *
   * @param id the session's id
   * @param now when it ended
   * @return whether it was ended by this call
   */
  boolean end(UUID id, Instant now);

  /**
   * Deletes up to {@code limit} dead sessions: ended ones, and ones last seen before {@code
   * seenBefore}.
   *
   * @param seenBefore a session last seen before this is dead
   * @param limit the most rows to delete
   * @return how many were deleted
   */
  int deleteDead(Instant seenBefore, int limit);
}

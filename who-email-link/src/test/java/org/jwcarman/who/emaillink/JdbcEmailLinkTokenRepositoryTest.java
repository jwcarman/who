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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class JdbcEmailLinkTokenRepositoryTest extends AbstractEmailLinkTest {

  private static final Duration INTERVAL = Duration.ofSeconds(60);

  @Autowired private EmailLinkTokenRepository tokens;
  @Autowired private EmailCredentialRepository credentials;
  @Autowired private JdbcClient jdbcClient;

  private final Instant now = Instant.parse("2026-11-07T12:00:00Z");

  private EmailCredential credential() {
    return credentials.save(EmailCredential.create(UUID.randomUUID() + "@example.com"));
  }

  @Test
  void firstIssueInserts() {
    EmailCredential c = credential();
    assertThat(tokens.issue(c.id(), "h1", "code-h1", now, now.plusSeconds(300), INTERVAL)).isTrue();
  }

  @Test
  void issueWithinTheResendIntervalChangesNothing() {
    EmailCredential c = credential();
    tokens.issue(c.id(), "h1", "code-h1", now, now.plusSeconds(300), INTERVAL);

    Instant later = now.plusSeconds(10);
    assertThat(tokens.issue(c.id(), "h2", "code-h2", later, later.plusSeconds(300), INTERVAL))
        .isFalse();

    assertThat(tokens.consume("h2", later)).isEmpty();
    assertThat(tokens.consume("h1", later)).contains(c.id());
  }

  @Test
  void issueAfterTheIntervalReplacesTheToken() {
    EmailCredential c = credential();
    tokens.issue(c.id(), "h1", "code-h1", now, now.plusSeconds(300), INTERVAL);

    Instant later = now.plusSeconds(61);
    assertThat(tokens.issue(c.id(), "h2", "code-h2", later, later.plusSeconds(300), INTERVAL))
        .isTrue();

    assertThat(tokens.consume("h1", later)).isEmpty();
    assertThat(tokens.consume("h2", later)).contains(c.id());
  }

  @Test
  void consumeIsSingleUse() {
    EmailCredential c = credential();
    tokens.issue(c.id(), "h1", "code-h1", now, now.plusSeconds(300), INTERVAL);

    assertThat(tokens.consume("h1", now)).contains(c.id());
    assertThat(tokens.consume("h1", now)).isEmpty();
  }

  @Test
  void expiredTokenDoesNotConsume() {
    EmailCredential c = credential();
    tokens.issue(c.id(), "h1", "code-h1", now, now.plusSeconds(300), INTERVAL);

    assertThat(tokens.consume("h1", now.plusSeconds(301))).isEmpty();
  }

  @Test
  void deleteRemovesTheMatchingToken() {
    EmailCredential c = credential();
    tokens.issue(c.id(), "h1", "code-h1", now, now.plusSeconds(300), INTERVAL);

    tokens.delete(c.id(), "h1");

    assertThat(tokens.consume("h1", now)).isEmpty();
  }

  @Test
  void deleteWithAStaleHashLeavesTheNewerToken() {
    EmailCredential c = credential();
    tokens.issue(c.id(), "h1", "code-h1", now, now.plusSeconds(300), INTERVAL);
    Instant later = now.plus(INTERVAL).plusSeconds(1);
    tokens.issue(c.id(), "h2", "code-h2", later, later.plusSeconds(300), INTERVAL);

    tokens.delete(c.id(), "h1");

    assertThat(tokens.consume("h2", later)).contains(c.id());
  }

  @Test
  void deletingTheCredentialDeletesItsToken() {
    EmailCredential c = credential();
    tokens.issue(c.id(), "h1", "code-h1", now, now.plusSeconds(300), INTERVAL);

    credentials.deleteById(c.id());

    assertThat(
            jdbcClient
                .sql("SELECT count(*) FROM who_email_link_token WHERE credential_id = :id")
                .param("id", c.id())
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void concurrentConsumesOneWins() throws Exception {
    EmailCredential c =
        credentials.save(EmailCredential.create(UUID.randomUUID() + "@example.com"));
    try {
      Instant at = Instant.now();
      tokens.issue(c.id(), "hc", "code-hc", at, at.plusSeconds(300), INTERVAL);
      CountDownLatch start = new CountDownLatch(1);
      Callable<Optional<UUID>> attempt =
          () -> {
            start.await();
            return tokens.consume("hc", at);
          };
      try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
        var a = pool.submit(attempt);
        var b = pool.submit(attempt);
        start.countDown();
        List<Optional<UUID>> results = List.of(a.get(), b.get());
        assertThat(results).filteredOn(Optional::isPresent).hasSize(1);
      }
    } finally {
      credentials.deleteById(c.id());
    }
  }

  private static final int MAX = 5;

  private int attemptsOf(UUID credentialId) {
    return jdbcClient
        .sql("SELECT code_attempts FROM who_email_link_token WHERE credential_id = :id")
        .param("id", credentialId)
        .query(Integer.class)
        .single();
  }

  @Test
  void attemptCodeReturnsTheHashAndCountsTheAttempt() {
    EmailCredential c = credential();
    tokens.issue(c.id(), "h1", "code-h1", now, now.plusSeconds(300), INTERVAL);

    Optional<EmailCodeAttempt> attempt = tokens.attemptCode(c.address(), MAX, now);

    assertThat(attempt).contains(new EmailCodeAttempt(c.id(), "code-h1"));
    assertThat(attemptsOf(c.id())).isEqualTo(1);
  }

  @Test
  void attemptCodeStopsAtMaxAttempts() {
    EmailCredential c = credential();
    tokens.issue(c.id(), "h1", "code-h1", now, now.plusSeconds(300), INTERVAL);

    for (int i = 0; i < MAX; i++) {
      assertThat(tokens.attemptCode(c.address(), MAX, now)).isPresent();
    }

    assertThat(tokens.attemptCode(c.address(), MAX, now)).isEmpty();
  }

  @Test
  void attemptCodeIgnoresExpiredRowsAndDoesNotCount() {
    EmailCredential c = credential();
    tokens.issue(c.id(), "h1", "code-h1", now, now.plusSeconds(300), INTERVAL);

    assertThat(tokens.attemptCode(c.address(), MAX, now.plusSeconds(301))).isEmpty();
    assertThat(attemptsOf(c.id())).isZero();
  }

  @Test
  void attemptCodeForAnUnknownAddressIsEmpty() {
    assertThat(tokens.attemptCode("nobody@example.com", MAX, now)).isEmpty();
  }

  @Test
  void reissueResetsAttempts() {
    EmailCredential c = credential();
    tokens.issue(c.id(), "h1", "code-h1", now, now.plusSeconds(300), INTERVAL);
    for (int i = 0; i < MAX; i++) {
      tokens.attemptCode(c.address(), MAX, now);
    }
    Instant later = now.plusSeconds(61);
    tokens.issue(c.id(), "h2", "code-h2", later, later.plusSeconds(300), INTERVAL);

    assertThat(tokens.attemptCode(c.address(), MAX, later))
        .contains(new EmailCodeAttempt(c.id(), "code-h2"));
  }

  @Test
  void linkStillWorksAfterTheCodeIsBurnt() {
    EmailCredential c = credential();
    tokens.issue(c.id(), "h1", "code-h1", now, now.plusSeconds(300), INTERVAL);
    for (int i = 0; i < MAX; i++) {
      tokens.attemptCode(c.address(), MAX, now);
    }

    assertThat(tokens.consume("h1", now)).contains(c.id());
  }

  @Test
  void consumeCodeIsSingleUse() {
    EmailCredential c = credential();
    tokens.issue(c.id(), "h1", "code-h1", now, now.plusSeconds(300), INTERVAL);

    assertThat(tokens.consumeCode(c.id(), "code-h1", now)).contains(c.id());
    assertThat(tokens.consumeCode(c.id(), "code-h1", now)).isEmpty();
  }

  @Test
  void codeFailsAfterTheLinkWasUsed() {
    EmailCredential c = credential();
    tokens.issue(c.id(), "h1", "code-h1", now, now.plusSeconds(300), INTERVAL);

    tokens.consume("h1", now);

    assertThat(tokens.consumeCode(c.id(), "code-h1", now)).isEmpty();
  }

  @Test
  void linkFailsAfterTheCodeWasUsed() {
    EmailCredential c = credential();
    tokens.issue(c.id(), "h1", "code-h1", now, now.plusSeconds(300), INTERVAL);

    tokens.consumeCode(c.id(), "code-h1", now);

    assertThat(tokens.consume("h1", now)).isEmpty();
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void concurrentWrongGuessesNeverExceedTheCap() throws Exception {
    EmailCredential c = credential();
    try {
      Instant at = Instant.now();
      tokens.issue(c.id(), "hw", "code-hw", at, at.plusSeconds(300), INTERVAL);
      List<Optional<EmailCodeAttempt>> results =
          runConcurrently(20, () -> tokens.attemptCode(c.address(), MAX, at));
      assertThat(results).filteredOn(Optional::isPresent).hasSize(MAX);
    } finally {
      credentials.deleteById(c.id());
    }
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void concurrentCorrectCodesProduceExactlyOneWinner() throws Exception {
    EmailCredential c = credential();
    try {
      Instant at = Instant.now();
      tokens.issue(c.id(), "hk", "code-hk", at, at.plusSeconds(300), INTERVAL);
      List<Optional<UUID>> results =
          runConcurrently(10, () -> tokens.consumeCode(c.id(), "code-hk", at));
      assertThat(results).filteredOn(Optional::isPresent).hasSize(1);
    } finally {
      credentials.deleteById(c.id());
    }
  }

  private static <T> List<T> runConcurrently(int threads, Callable<T> task) throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    Callable<T> gated =
        () -> {
          start.await();
          return task.call();
        };
    try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
      List<Future<T>> futures = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        futures.add(pool.submit(gated));
      }
      start.countDown();
      List<T> results = new ArrayList<>();
      for (Future<T> future : futures) {
        results.add(future.get());
      }
      return results;
    }
  }
}

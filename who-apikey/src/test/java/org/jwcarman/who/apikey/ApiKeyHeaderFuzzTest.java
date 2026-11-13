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
package org.jwcarman.who.apikey;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.jwcarman.who.core.service.WhoService;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import com.code_intelligence.jazzer.junit.FuzzTest;

/**
 * Fuzzes the API-key header, which any caller can send. Runs as a quick regression test in every
 * build; {@code JAZZER_FUZZ=1} generates new inputs.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ApiKeyHeaderFuzzTest {

  private static final String HEADER = "X-API-Key";

  private static final RecordingRepository REPOSITORY = new RecordingRepository();

  private static final ApiKeyAuthenticationFilter FILTER =
      new ApiKeyAuthenticationFilter(REPOSITORY, mock(WhoService.class), HEADER);

  @FuzzTest(maxDuration = "2m")
  void any_header_is_looked_up_by_its_hash_and_never_breaks_the_chain(byte[] header)
      throws Exception {
    // servlet containers decode header bytes as ISO-8859-1
    String value = new String(header, ISO_8859_1);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(HEADER, value);
    MockFilterChain chain = new MockFilterChain();
    REPOSITORY.lookups.clear();
    SecurityContextHolder.clearContext();

    try {
      FILTER.doFilter(request, new MockHttpServletResponse(), chain);

      assertThat(chain.getRequest()).isSameAs(request);
      assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
      if (value.isBlank()) {
        assertThat(REPOSITORY.lookups).isEmpty();
      } else {
        assertThat(REPOSITORY.lookups).containsExactly(sha256Hex(value));
      }
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private static String sha256Hex(String value) throws NoSuchAlgorithmException {
    return HexFormat.of()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(UTF_8)));
  }

  /** Knows no keys; records the hashes it is asked for. */
  private static final class RecordingRepository implements ApiKeyCredentialRepository {

    private final List<String> lookups = new ArrayList<>();

    @Override
    public Optional<ApiKeyCredential> findByKeyHash(String keyHash) {
      lookups.add(keyHash);
      return Optional.empty();
    }

    @Override
    public ApiKeyCredential save(ApiKeyCredential credential) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean deleteById(UUID id) {
      throw new UnsupportedOperationException();
    }
  }
}

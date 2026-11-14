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
package org.jwcarman.who.enrollment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The default methods an enrollment token repository gets if it does not override them. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class EnrollmentTokenRepositoryDefaultsTest {

  @Nested
  class RevokingPendingTokens {

    @Test
    void revokes_them_all_but_reports_no_ids() {
      EnrollmentTokenRepository repository = mock(EnrollmentTokenRepository.class);
      when(repository.revokePendingForIdentity(any())).thenCallRealMethod();
      UUID identityId = UUID.randomUUID();

      assertThat(repository.revokePendingForIdentity(identityId)).isEmpty();
      verify(repository).revokeAllPendingForIdentity(identityId);
    }
  }
}

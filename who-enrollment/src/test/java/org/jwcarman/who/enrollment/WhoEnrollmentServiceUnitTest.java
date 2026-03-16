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

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jwcarman.who.core.crypto.MessageDigests;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.core.spi.Credential;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WhoEnrollmentServiceUnitTest {

  @Mock private EnrollmentTokenRepository enrollmentTokenRepository;
  @Mock private IdentityRepository identityRepository;
  @Mock private CredentialIdentityRepository credentialIdentityRepository;

  @Test
  void enrollThrowsWhenIdentityDisappearsAfterTokenRedemption() {
    Identity identity = Identity.create();
    EnrollmentToken token = EnrollmentToken.create(identity, Duration.ofHours(1));
    Credential credential = UUID::randomUUID;
    String tokenValue = token.value();

    when(enrollmentTokenRepository.findByValue(MessageDigests.sha256Hex(tokenValue)))
        .thenReturn(Optional.of(token));
    when(enrollmentTokenRepository.save(any())).thenReturn(token);
    when(identityRepository.findById(identity.id())).thenReturn(Optional.empty());

    WhoEnrollmentService service =
        new WhoEnrollmentService(
            enrollmentTokenRepository,
            identityRepository,
            credentialIdentityRepository,
            Duration.ofHours(24));

    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(() -> service.enroll(tokenValue, credential));
  }
}

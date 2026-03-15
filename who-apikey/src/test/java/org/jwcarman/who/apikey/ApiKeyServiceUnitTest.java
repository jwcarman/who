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

import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ApiKeyServiceUnitTest {

  @Mock private ApiKeyCredentialRepository apiKeyCredentialRepository;

  @Mock private CredentialIdentityRepository credentialIdentityRepository;

  @Test
  void createThrowsWhenIdentityIdIsNull() {
    ApiKeyService service =
        new ApiKeyService(apiKeyCredentialRepository, credentialIdentityRepository);

    assertThatNullPointerException().isThrownBy(() -> service.create(null, "My Key"));
  }

  @Test
  void createThrowsWhenNameIsNull() {
    ApiKeyService service =
        new ApiKeyService(apiKeyCredentialRepository, credentialIdentityRepository);
    Identity identity = Identity.create();

    assertThatNullPointerException().isThrownBy(() -> service.create(identity, null));
  }
}

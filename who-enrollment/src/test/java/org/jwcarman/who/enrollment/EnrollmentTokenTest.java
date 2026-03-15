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

import org.jwcarman.who.core.domain.Identity;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class EnrollmentTokenTest {

    @Test
    void isPendingReturnsTrueForPendingNonExpiredToken() {
        EnrollmentToken token = EnrollmentToken.create(Identity.create(), Duration.ofHours(24));

        assertThat(token.isPending()).isTrue();
        assertThat(token.isExpired()).isFalse();
    }

    @Test
    void isPendingReturnsFalseForExpiredToken() {
        EnrollmentToken token = EnrollmentToken.create(Identity.create(), Duration.ofHours(-1));

        assertThat(token.isExpired()).isTrue();
        assertThat(token.isPending()).isFalse();
    }

    @Test
    void isPendingReturnsFalseForRedeemedToken() {
        EnrollmentToken token = EnrollmentToken.create(Identity.create(), Duration.ofHours(24)).redeem();

        assertThat(token.isPending()).isFalse();
    }

    @Test
    void isPendingReturnsFalseForRevokedToken() {
        EnrollmentToken token = EnrollmentToken.create(Identity.create(), Duration.ofHours(24)).revoke();

        assertThat(token.isPending()).isFalse();
    }
}

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
package org.jwcarman.who.core.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InvitationStatusTest {

    @Test
    void shouldHaveFourStatuses() {
        assertThat(InvitationStatus.values())
            .hasSize(4)
            .containsExactlyInAnyOrder(
                InvitationStatus.PENDING,
                InvitationStatus.ACCEPTED,
                InvitationStatus.EXPIRED,
                InvitationStatus.REVOKED
            );
    }

    @Test
    void shouldConvertFromString() {
        assertThat(InvitationStatus.valueOf("PENDING")).isEqualTo(InvitationStatus.PENDING);
        assertThat(InvitationStatus.valueOf("ACCEPTED")).isEqualTo(InvitationStatus.ACCEPTED);
        assertThat(InvitationStatus.valueOf("EXPIRED")).isEqualTo(InvitationStatus.EXPIRED);
        assertThat(InvitationStatus.valueOf("REVOKED")).isEqualTo(InvitationStatus.REVOKED);
    }
}

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
package org.jwcarman.who.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.sql.init.DatabaseInitializationMode;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class WhoPropertiesTest {

    @Test
    void defaultsArePopulated() {
        WhoProperties props = new WhoProperties();

        assertThat(props.getInitializeSchema()).isEqualTo(DatabaseInitializationMode.EMBEDDED);
        assertThat(props.getEnrollment().getTokenExpiration()).isEqualTo(Duration.ofHours(24));
        assertThat(props.getApiKey().getHeaderName()).isEqualTo("X-API-Key");
    }

    @Test
    void settersRoundTrip() {
        WhoProperties props = new WhoProperties();

        props.setInitializeSchema(DatabaseInitializationMode.ALWAYS);
        assertThat(props.getInitializeSchema()).isEqualTo(DatabaseInitializationMode.ALWAYS);

        WhoProperties.Enrollment enrollment = new WhoProperties.Enrollment();
        enrollment.setTokenExpiration(Duration.ofHours(48));
        props.setEnrollment(enrollment);
        assertThat(props.getEnrollment().getTokenExpiration()).isEqualTo(Duration.ofHours(48));

        WhoProperties.ApiKey apiKey = new WhoProperties.ApiKey();
        apiKey.setHeaderName("Authorization");
        props.setApiKey(apiKey);
        assertThat(props.getApiKey().getHeaderName()).isEqualTo("Authorization");
    }
}

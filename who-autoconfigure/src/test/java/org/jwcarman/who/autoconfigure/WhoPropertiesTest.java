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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WhoPropertiesTest {

    @Test
    void defaultsArePopulated() {
        WhoProperties props = new WhoProperties();

        assertThat(props.getJdbc().getSchemaLocations())
                .containsExactly("classpath:org/jwcarman/who/jdbc/schema.sql");
        assertThat(props.getRbac().getSchemaLocations())
                .containsExactly("classpath:org/jwcarman/who/rbac/schema.sql");
        assertThat(props.getJwt().getSchemaLocations())
                .containsExactly("classpath:org/jwcarman/who/jwt/schema.sql");
        assertThat(props.getEnrollment().getExpirationHours()).isEqualTo(24);
        assertThat(props.getApiKey().getHeaderName()).isEqualTo("X-API-Key");
    }

    @Test
    void settersRoundTrip() {
        WhoProperties props = new WhoProperties();

        WhoProperties.Jdbc jdbc = new WhoProperties.Jdbc();
        jdbc.setSchemaLocations(List.of("classpath:custom-jdbc.sql"));
        props.setJdbc(jdbc);
        assertThat(props.getJdbc().getSchemaLocations()).containsExactly("classpath:custom-jdbc.sql");

        WhoProperties.Rbac rbac = new WhoProperties.Rbac();
        rbac.setSchemaLocations(List.of("classpath:custom-rbac.sql"));
        props.setRbac(rbac);
        assertThat(props.getRbac().getSchemaLocations()).containsExactly("classpath:custom-rbac.sql");

        WhoProperties.Jwt jwt = new WhoProperties.Jwt();
        jwt.setSchemaLocations(List.of("classpath:custom-jwt.sql"));
        props.setJwt(jwt);
        assertThat(props.getJwt().getSchemaLocations()).containsExactly("classpath:custom-jwt.sql");

        WhoProperties.Enrollment enrollment = new WhoProperties.Enrollment();
        enrollment.setExpirationHours(48);
        props.setEnrollment(enrollment);
        assertThat(props.getEnrollment().getExpirationHours()).isEqualTo(48);

        WhoProperties.ApiKey apiKey = new WhoProperties.ApiKey();
        apiKey.setHeaderName("Authorization");
        props.setApiKey(apiKey);
        assertThat(props.getApiKey().getHeaderName()).isEqualTo("Authorization");
    }
}

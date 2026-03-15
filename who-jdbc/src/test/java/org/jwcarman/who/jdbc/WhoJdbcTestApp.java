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
package org.jwcarman.who.jdbc;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import javax.sql.DataSource;

@SpringBootApplication
class WhoJdbcTestApp {

    /**
     * Creates an H2 in-memory DataSource in PostgreSQL compatibility mode.
     * The database name includes MODE=PostgreSQL so Spring's EmbeddedDatabaseBuilder
     * produces the URL: jdbc:h2:mem:whotest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1
     */
    @Bean
    DataSource dataSource() {
        return new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("whotest;MODE=PostgreSQL")
                .addScript("classpath:org/jwcarman/who/jdbc/schema.sql")
                .build();
    }
}

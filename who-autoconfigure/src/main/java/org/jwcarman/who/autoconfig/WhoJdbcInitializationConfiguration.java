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
package org.jwcarman.who.autoconfig;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.EmbeddedDatabaseConnection;
import org.springframework.boot.sql.init.DatabaseInitializationMode;
import org.springframework.boot.sql.init.DatabaseInitializationSettings;
import org.springframework.boot.sql.init.dependency.DatabaseInitializationDependencyConfigurer;
import org.springframework.boot.sql.init.SqlDataSourceScriptDatabaseInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import javax.sql.DataSource;
import java.util.List;

/**
 * Auto-configuration for Who JDBC schema initialization.
 * Uses Spring Boot's built-in SQL initialization with automatic platform detection.
 */
@Configuration
@ConditionalOnClass(DataSource.class)
@EnableConfigurationProperties(WhoJdbcProperties.class)
@Import(DatabaseInitializationDependencyConfigurer.class)
public class WhoJdbcInitializationConfiguration {

    @Bean
    public SqlDataSourceScriptDatabaseInitializer whoDataSourceInitializer(
            DataSource dataSource,
            WhoJdbcProperties properties) {

        DatabaseInitializationSettings settings = new DatabaseInitializationSettings();
        settings.setSchemaLocations(List.of("classpath:org/jwcarman/who/jdbc/schema.sql"));
        settings.setDataLocations(List.of("classpath:org/jwcarman/who/jdbc/data.sql"));
        settings.setContinueOnError(false);

        // Map our mode to Spring Boot's mode
        settings.setMode(mapMode(dataSource, properties.getInitializeSchema()));

        return new SqlDataSourceScriptDatabaseInitializer(dataSource, settings);
    }

    private DatabaseInitializationMode mapMode(
            DataSource dataSource,
            WhoJdbcProperties.DatabaseInitializationMode mode) {

        return switch (mode) {
            case ALWAYS -> DatabaseInitializationMode.ALWAYS;
            case EMBEDDED -> EmbeddedDatabaseConnection.isEmbedded(dataSource)
                    ? DatabaseInitializationMode.ALWAYS
                    : DatabaseInitializationMode.NEVER;
            case NEVER -> DatabaseInitializationMode.NEVER;
        };
    }
}

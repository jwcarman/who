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
import org.springframework.boot.sql.init.dependency.DatabaseInitializationDependencyConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.DataSourceInitializer;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;

/**
 * Auto-configuration for Who JDBC schema initialization.
 */
@Configuration
@ConditionalOnClass(DataSource.class)
@EnableConfigurationProperties(WhoJdbcProperties.class)
@Import(DatabaseInitializationDependencyConfigurer.class)
public class WhoJdbcInitializationConfiguration {

    @Bean
    public DataSourceInitializer whoDataSourceInitializer(DataSource dataSource, WhoJdbcProperties properties) {
        DataSourceInitializer initializer = new DataSourceInitializer();
        initializer.setDataSource(dataSource);

        // Determine if initialization should be enabled
        boolean enabled = shouldInitialize(dataSource, properties.getInitializeSchema());
        initializer.setEnabled(enabled);

        if (enabled) {
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
            populator.addScript(new ClassPathResource("org/jwcarman/who/jdbc/schema.sql"));

            // Use database-specific data file
            String dataScript = getDataScript(dataSource);
            populator.addScript(new ClassPathResource(dataScript));
            populator.setContinueOnError(false);
            initializer.setDatabasePopulator(populator);
        }

        return initializer;
    }

    private String getDataScript(DataSource dataSource) {
        // Check if H2 embedded database
        if (EmbeddedDatabaseConnection.isEmbedded(dataSource)) {
            EmbeddedDatabaseConnection connection = EmbeddedDatabaseConnection.get(dataSource.getClass().getClassLoader());
            if (connection == EmbeddedDatabaseConnection.H2) {
                return "org/jwcarman/who/jdbc/data-h2.sql";
            }
        }
        // Default to PostgreSQL
        return "org/jwcarman/who/jdbc/data-postgresql.sql";
    }

    private boolean shouldInitialize(DataSource dataSource, WhoJdbcProperties.DatabaseInitializationMode mode) {
        return switch (mode) {
            case ALWAYS -> true;
            case EMBEDDED -> EmbeddedDatabaseConnection.isEmbedded(dataSource);
            case NEVER -> false;
        };
    }
}

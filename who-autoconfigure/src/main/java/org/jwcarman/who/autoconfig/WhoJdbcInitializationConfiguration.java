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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
    @ConditionalOnProperty(prefix = "who.jdbc", name = "initialize-schema", havingValue = "always", matchIfMissing = true)
    public DataSourceInitializer whoDataSourceInitializer(DataSource dataSource, WhoJdbcProperties properties) {
        DataSourceInitializer initializer = new DataSourceInitializer();
        initializer.setDataSource(dataSource);
        initializer.setEnabled(true);

        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.addScript(new ClassPathResource("org/jwcarman/who/jdbc/schema.sql"));
        populator.addScript(new ClassPathResource("org/jwcarman/who/jdbc/data.sql"));
        populator.setContinueOnError(false);

        initializer.setDatabasePopulator(populator);
        return initializer;
    }
}

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

import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.jwt.WhoJwtAuthenticationConverter;
import org.jwcarman.who.rbac.RbacPermissionsResolver;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

class WhoAutoConfigurationTest {

    @Configuration
    static class TestDataSourceConfig {
        @Bean
        DataSource dataSource() {
            return new EmbeddedDatabaseBuilder()
                    .setType(EmbeddedDatabaseType.H2)
                    .build();
        }

        @Bean
        JdbcClient jdbcClient(DataSource dataSource) {
            return JdbcClient.create(dataSource);
        }
    }

    @Test
    void allModulesOnClasspathCreatesWhoServiceAndOptionalBeans() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(WhoAutoConfiguration.class))
                .withUserConfiguration(TestDataSourceConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(WhoService.class);
                    assertThat(context).hasSingleBean(RbacPermissionsResolver.class);
                    assertThat(context).hasSingleBean(WhoJwtAuthenticationConverter.class);
                });
    }

    @Test
    void jdbcOnlyContextLoadsWithoutErrors() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(WhoAutoConfiguration.class))
                .withUserConfiguration(TestDataSourceConfig.class)
                .withClassLoader(new FilteredClassLoader(
                        RbacPermissionsResolver.class,
                        WhoJwtAuthenticationConverter.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(WhoService.class);
                    assertThat(context).doesNotHaveBean(RbacPermissionsResolver.class);
                    assertThat(context).doesNotHaveBean(WhoJwtAuthenticationConverter.class);
                });
    }
}

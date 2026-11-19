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

import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseDataSource;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseProperties;
import org.springframework.boot.sql.init.dependency.DatabaseInitializationDependencyConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.util.StringUtils;

/**
 * Migrates who's own tables with Liquibase at startup, before the application's migrations.
 *
 * <p>{@link WhoLiquibase} runs when Spring Boot's Liquibase support and {@code who-jdbc} are on the
 * classpath, unless {@code spring.liquibase.enabled} or {@code who.migrations.enabled} is {@code
 * false}.
 */
@AutoConfiguration(
    afterName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@Import(DatabaseInitializationDependencyConfigurer.class)
public class WhoMigrationsAutoConfiguration {

  /** Creates the autoconfiguration; Spring Boot instantiates it. */
  public WhoMigrationsAutoConfiguration() {
    // no state
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(
      name = {
        "liquibase.integration.spring.SpringLiquibase",
        "org.springframework.boot.liquibase.autoconfigure.LiquibaseProperties",
        "org.jwcarman.who.jdbc.JdbcIdentityRepository"
      })
  @ConditionalOnBooleanProperty(name = "spring.liquibase.enabled", matchIfMissing = true)
  @ConditionalOnBooleanProperty(name = "who.migrations.enabled", matchIfMissing = true)
  @ConditionalOnBean(DataSource.class)
  @EnableConfigurationProperties(LiquibaseProperties.class)
  static class LiquibaseMigrationsConfiguration {

    @Bean
    WhoLiquibase whoLiquibase(
        ObjectProvider<DataSource> dataSource,
        @LiquibaseDataSource ObjectProvider<DataSource> liquibaseDataSource,
        LiquibaseProperties properties,
        Environment environment,
        ResourceLoader resourceLoader) {
      return new WhoLiquibase(
          migrationDataSource(dataSource, liquibaseDataSource, properties, environment),
          properties,
          resourceLoader);
    }

    /** The data source Spring Boot's Liquibase would migrate the application's tables in. */
    private static DataSource migrationDataSource(
        ObjectProvider<DataSource> dataSource,
        ObjectProvider<DataSource> liquibaseDataSource,
        LiquibaseProperties properties,
        Environment environment) {
      DataSource own = liquibaseDataSource.getIfAvailable();
      if (own != null) {
        return own;
      }
      String url = properties.getUrl();
      if (url == null) {
        return dataSource.getObject();
      }
      DataSourceBuilder<?> builder =
          DataSourceBuilder.create()
              .type(SimpleDriverDataSource.class)
              .url(url)
              .username(
                  orElse(
                      properties.getUser(), environment.getProperty("spring.datasource.username")))
              .password(
                  orElse(
                      properties.getPassword(),
                      environment.getProperty("spring.datasource.password")));
      if (StringUtils.hasText(properties.getDriverClassName())) {
        builder.driverClassName(properties.getDriverClassName());
      }
      return builder.build();
    }

    private static String orElse(String value, String fallback) {
      return value != null ? value : fallback;
    }
  }
}

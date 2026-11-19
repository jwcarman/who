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
import org.springframework.boot.liquibase.autoconfigure.LiquibaseConnectionDetails;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseDataSource;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseProperties;
import org.springframework.boot.sql.init.dependency.DatabaseInitializationDependencyConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
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
        ObjectProvider<LiquibaseConnectionDetails> connectionDetails,
        LiquibaseProperties properties,
        ResourceLoader resourceLoader) {
      return new WhoLiquibase(
          migrationDataSource(
              liquibaseDataSource.getIfAvailable(),
              dataSource,
              connectionDetails.getIfAvailable(() -> new PropertiesConnectionDetails(properties))),
          properties,
          resourceLoader);
    }

    /**
     * The data source Spring Boot's Liquibase migrates the application's tables in, chosen in the
     * same order as Boot's {@code LiquibaseAutoConfiguration}.
     */
    private static DataSource migrationDataSource(
        DataSource liquibaseDataSource,
        ObjectProvider<DataSource> dataSource,
        LiquibaseConnectionDetails connectionDetails) {
      if (liquibaseDataSource != null) {
        return liquibaseDataSource;
      }
      String url = connectionDetails.getJdbcUrl();
      if (url != null) {
        DataSourceBuilder<?> builder =
            DataSourceBuilder.create().type(SimpleDriverDataSource.class).url(url);
        applyConnectionDetails(connectionDetails, builder);
        return builder.build();
      }
      DataSource primary = dataSource.getObject();
      if (connectionDetails.getUsername() != null) {
        DataSourceBuilder<?> builder =
            DataSourceBuilder.derivedFrom(primary).type(SimpleDriverDataSource.class);
        applyConnectionDetails(connectionDetails, builder);
        return builder.build();
      }
      return primary;
    }

    private static void applyConnectionDetails(
        LiquibaseConnectionDetails connectionDetails, DataSourceBuilder<?> builder) {
      builder.username(connectionDetails.getUsername());
      builder.password(connectionDetails.getPassword());
      String driverClassName = connectionDetails.getDriverClassName();
      if (StringUtils.hasText(driverClassName)) {
        builder.driverClassName(driverClassName);
      }
    }
  }

  /**
   * {@code spring.liquibase.*} as connection details, as Spring Boot reads them, for when Boot's
   * Liquibase configuration stood down and registered none.
   */
  private static final class PropertiesConnectionDetails implements LiquibaseConnectionDetails {

    private final LiquibaseProperties properties;

    private PropertiesConnectionDetails(LiquibaseProperties properties) {
      this.properties = properties;
    }

    @Override
    public String getUsername() {
      return properties.getUser();
    }

    @Override
    public String getPassword() {
      return properties.getPassword();
    }

    @Override
    public String getJdbcUrl() {
      return properties.getUrl();
    }

    @Override
    public String getDriverClassName() {
      String driverClassName = properties.getDriverClassName();
      return driverClassName != null
          ? driverClassName
          : LiquibaseConnectionDetails.super.getDriverClassName();
    }
  }
}

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

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseProperties;
import org.springframework.core.Ordered;
import org.springframework.core.io.ResourceLoader;

import liquibase.UpdateSummaryEnum;
import liquibase.UpdateSummaryOutputEnum;
import liquibase.exception.LiquibaseException;
import liquibase.integration.spring.SpringLiquibase;
import liquibase.ui.UIServiceEnum;

/**
 * Runs who's own Liquibase changelog when it is initialized, ahead of the application's.
 *
 * <p>It runs against the data source, schemas and history tables Spring Boot's Liquibase uses for
 * the application, so who's tables land where the application's do and who's changesets are
 * recorded beside the application's. Every {@link SpringLiquibase} bean in the context depends on
 * it. A library whose changesets reference who's tables registers a database initializer detector
 * ordered at {@link #INITIALIZER_ORDER} {@code + 1} for its own runner, and migrates {@link
 * #getDataSource()}.
 */
public final class WhoLiquibase implements InitializingBean {

  /** who's master changelog, which includes the changelog of each who module present. */
  public static final String CHANGELOG = "classpath:org/jwcarman/who/db/who-changelog.xml";

  /**
   * The order of the {@code DatabaseInitializerDetector} that finds this runner, ahead of every
   * other detector. Spring Boot makes each detector's initializers depend on those of the detector
   * before it, so a library whose detector returns {@code INITIALIZER_ORDER + 1} runs after who and
   * before the application's {@link SpringLiquibase} beans, whose detector has order {@code 0}.
   */
  public static final int INITIALIZER_ORDER = Ordered.HIGHEST_PRECEDENCE;

  private final DataSource dataSource;
  private final LiquibaseProperties properties;
  private final ResourceLoader resourceLoader;

  /**
   * Creates the runner.
   *
   * @param dataSource the data source who's tables live in
   * @param properties Spring Boot's Liquibase settings, for the schemas and history tables
   * @param resourceLoader the loader that resolves the changelog's {@code classpath:} includes
   */
  public WhoLiquibase(
      DataSource dataSource, LiquibaseProperties properties, ResourceLoader resourceLoader) {
    this.dataSource = dataSource;
    this.properties = properties;
    this.resourceLoader = resourceLoader;
  }

  /**
   * Returns the data source who migrated, which is the one Spring Boot's Liquibase migrates the
   * application's tables in. A library whose tables belong beside who's migrates this one.
   *
   * @return the data source who's tables live in
   */
  public DataSource getDataSource() {
    return dataSource;
  }

  /**
   * Runs who's changelog.
   *
   * @throws LiquibaseException when a changeset fails, which fails startup
   */
  @Override
  public void afterPropertiesSet() throws LiquibaseException {
    springLiquibase().afterPropertiesSet();
  }

  /**
   * Builds the Liquibase runner for who's changelog from Spring Boot's settings.
   *
   * @return the runner, not yet run
   */
  SpringLiquibase springLiquibase() {
    SpringLiquibase liquibase = new SpringLiquibase();
    liquibase.setDataSource(dataSource);
    liquibase.setResourceLoader(resourceLoader);
    liquibase.setChangeLog(CHANGELOG);
    liquibase.setDefaultSchema(properties.getDefaultSchema());
    liquibase.setLiquibaseSchema(properties.getLiquibaseSchema());
    liquibase.setLiquibaseTablespace(properties.getLiquibaseTablespace());
    liquibase.setDatabaseChangeLogTable(properties.getDatabaseChangeLogTable());
    liquibase.setDatabaseChangeLogLockTable(properties.getDatabaseChangeLogLockTable());
    if (properties.getShowSummary() != null) {
      liquibase.setShowSummary(UpdateSummaryEnum.valueOf(properties.getShowSummary().name()));
    }
    if (properties.getShowSummaryOutput() != null) {
      liquibase.setShowSummaryOutput(
          UpdateSummaryOutputEnum.valueOf(properties.getShowSummaryOutput().name()));
    }
    if (properties.getUiService() != null) {
      liquibase.setUiService(UIServiceEnum.valueOf(properties.getUiService().name()));
    }
    if (properties.getAnalyticsEnabled() != null) {
      liquibase.setAnalyticsEnabled(properties.getAnalyticsEnabled());
    }
    if (properties.getLicenseKey() != null) {
      liquibase.setLicenseKey(properties.getLicenseKey());
    }
    return liquibase;
  }
}

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
import org.springframework.core.io.ResourceLoader;

import liquibase.exception.LiquibaseException;
import liquibase.integration.spring.SpringLiquibase;

/**
 * Runs who's own Liquibase changelog when it is initialized, ahead of the application's.
 *
 * <p>It runs against the data source, schemas and history tables Spring Boot's Liquibase uses for
 * the application, so who's tables land where the application's do and who's changesets are
 * recorded beside the application's. Every {@link SpringLiquibase} bean in the context depends on
 * it. A library whose changesets reference who's tables orders its own run after who's by depending
 * on this bean.
 */
public final class WhoLiquibase implements InitializingBean {

  /** who's master changelog, which includes the changelog of each who module present. */
  public static final String CHANGELOG = "classpath:org/jwcarman/who/db/who-changelog.xml";

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
   * Runs who's changelog.
   *
   * @throws LiquibaseException when a changeset fails, which fails startup
   */
  @Override
  public void afterPropertiesSet() throws LiquibaseException {
    SpringLiquibase liquibase = new SpringLiquibase();
    liquibase.setDataSource(dataSource);
    liquibase.setResourceLoader(resourceLoader);
    liquibase.setChangeLog(CHANGELOG);
    liquibase.setDefaultSchema(properties.getDefaultSchema());
    liquibase.setLiquibaseSchema(properties.getLiquibaseSchema());
    liquibase.setLiquibaseTablespace(properties.getLiquibaseTablespace());
    liquibase.setDatabaseChangeLogTable(properties.getDatabaseChangeLogTable());
    liquibase.setDatabaseChangeLogLockTable(properties.getDatabaseChangeLogLockTable());
    liquibase.afterPropertiesSet();
  }
}

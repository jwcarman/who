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

import java.util.Set;

import org.springframework.boot.sql.init.dependency.AbstractBeansOfTypeDatabaseInitializerDetector;
import org.springframework.core.Ordered;
import org.springframework.util.ClassUtils;

/**
 * Detects {@link WhoLiquibase} as a database initializer, ahead of every other detector.
 *
 * <p>Spring Boot makes each detector's initializers depend on those of the detector before it, so
 * running first makes every {@code SpringLiquibase} bean (Boot's, or the application's own) wait
 * for who's tables, and every bean that depends on database initialization wait for them too.
 */
public final class WhoLiquibaseDatabaseInitializerDetector
    extends AbstractBeansOfTypeDatabaseInitializerDetector {

  private static final String SPRING_LIQUIBASE = "liquibase.integration.spring.SpringLiquibase";

  /** Creates the detector; Spring Boot loads it from {@code spring.factories}. */
  public WhoLiquibaseDatabaseInitializerDetector() {
    // nothing to set up
  }

  /**
   * Returns {@link WhoLiquibase} when Liquibase is on the classpath, and nothing otherwise, so the
   * detector never loads a class that needs Liquibase.
   *
   * @return the initializer types this detector finds
   */
  @Override
  protected Set<Class<?>> getDatabaseInitializerBeanTypes() {
    if (ClassUtils.isPresent(SPRING_LIQUIBASE, getClass().getClassLoader())) {
      return Set.of(WhoLiquibase.class);
    }
    return Set.of();
  }

  /**
   * Returns the highest precedence, so who's run comes before every other initializer.
   *
   * @return {@link Ordered#HIGHEST_PRECEDENCE}
   */
  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }
}

package org.jwcarman.who.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Startup fails, saying what to do, when who-jdbc is present but Liquibase is not. */
class WhoMigrationsRequireLiquibaseTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  DataSourceAutoConfiguration.class, WhoMigrationsAutoConfiguration.class))
          .withPropertyValues(WhoTestPostgres.freshDatabaseProperties());

  @Test
  void failsWithoutLiquibase() {
    runner
        .withClassLoader(
            new FilteredClassLoader("liquibase.", "org.springframework.boot.liquibase."))
        .run(
            context ->
                assertThat(context)
                    .getFailure()
                    .rootCause()
                    .isInstanceOf(WhoConfigurationException.class)
                    .hasMessage(
                        "who.migrations.enabled is true, but Liquibase is not on the classpath. "
                            + "Add spring-boot-starter-liquibase, or set who.migrations.enabled=false and run "
                            + "classpath:org/jwcarman/who/db/who-changelog.xml yourself."));
  }

  @Test
  void failsWithLiquibaseCoreButNotSpringBootsLiquibaseSupport() {
    runner
        .withClassLoader(new FilteredClassLoader("org.springframework.boot.liquibase."))
        .run(
            context ->
                assertThat(context)
                    .getFailure()
                    .rootCause()
                    .isInstanceOf(WhoConfigurationException.class));
  }

  @Test
  void startsWithoutLiquibaseWhenMigrationsAreDisabled() {
    runner
        .withClassLoader(
            new FilteredClassLoader("liquibase.", "org.springframework.boot.liquibase."))
        .withPropertyValues("who.migrations.enabled=false")
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  void startsWithoutLiquibaseWhenWhoJdbcIsAbsent() {
    runner
        .withClassLoader(
            new FilteredClassLoader(
                "liquibase.", "org.springframework.boot.liquibase.", "org.jwcarman.who.jdbc."))
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  void detectorFindsNothingWhenLiquibaseIsMissing() {
    // the detector, loaded from spring.factories, must not need Liquibase classes
    assertThat(
            new WhoLiquibaseDatabaseInitializerDetector(new FilteredClassLoader("liquibase."))
                .detect(new DefaultListableBeanFactory()))
        .isEmpty();
  }
}

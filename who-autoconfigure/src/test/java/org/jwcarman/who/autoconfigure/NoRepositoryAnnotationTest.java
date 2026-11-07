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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Repository;

/**
 * {@code @Repository} makes Boot's exception-translation post-processor wrap the bean in a CGLIB
 * class proxy, which Spring AOT cannot pre-generate for who's interface-typed {@code @Bean}
 * methods. {@code JdbcClient} already translates exceptions, so who's classes must not carry it.
 */
class NoRepositoryAnnotationTest {

  @Test
  void noWhoClassIsARepositoryBean() {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(Repository.class));

    List<String> offenders =
        scanner.findCandidateComponents("org.jwcarman.who").stream()
            .map(BeanDefinition::getBeanClassName)
            .filter(name -> !name.startsWith("org.jwcarman.who.example"))
            .sorted()
            .toList();

    assertThat(offenders).isEmpty();
  }
}

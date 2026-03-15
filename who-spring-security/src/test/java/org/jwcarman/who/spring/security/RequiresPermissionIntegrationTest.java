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
package org.jwcarman.who.spring.security;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.stereotype.Service;

@SpringBootTest(
    classes = {
      RequiresPermissionIntegrationTest.TestConfig.class,
      RequiresPermissionIntegrationTest.ProtectedService.class
    })
class RequiresPermissionIntegrationTest {

  @Configuration
  @EnableMethodSecurity
  static class TestConfig {

    @Bean
    ProtectedService protectedService() {
      return new ProtectedService();
    }
  }

  @Service
  static class ProtectedService {

    @RequiresPermission("task.read")
    public String readTask() {
      return "ok";
    }
  }

  @Autowired private ProtectedService protectedService;

  @Test
  @WithMockUser(authorities = "task.read")
  void allowsAccessWhenAuthorityMatches() {
    assertThatNoException().isThrownBy(() -> protectedService.readTask());
  }

  @Test
  @WithMockUser(authorities = "task.write")
  void deniesAccessWhenAuthorityDoesNotMatch() {
    assertThatThrownBy(() -> protectedService.readTask()).isInstanceOf(AccessDeniedException.class);
  }

  @Test
  @WithMockUser
  void deniesAccessWhenNoAuthorities() {
    assertThatThrownBy(() -> protectedService.readTask()).isInstanceOf(AccessDeniedException.class);
  }
}

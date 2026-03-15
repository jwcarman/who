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

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Meta-annotation that expresses a {@link PreAuthorize @PreAuthorize} {@code hasAuthority(...)}
 * check as a named permission string.
 *
 * <p>Use directly on methods or types:
 *
 * <pre>{@code
 * @RequiresPermission("task.read")
 * public List<Task> getTasks() { ... }
 * }</pre>
 *
 * <p>Or as a meta-annotation to build a typed permission vocabulary:
 *
 * <pre>{@code
 * public interface TaskPermissions {
 *
 *     @Target(ElementType.METHOD)
 *     @Retention(RetentionPolicy.RUNTIME)
 *     @RequiresPermission("task.read")
 *     @interface Read {}
 *
 *     @Target(ElementType.METHOD)
 *     @Retention(RetentionPolicy.RUNTIME)
 *     @RequiresPermission("task.write")
 *     @interface Write {}
 * }
 * }</pre>
 *
 * <p>Controllers then use the inner annotation with no string at the call site:
 *
 * <pre>{@code
 * @TaskPermissions.Read
 * public List<Task> getTasks() { ... }
 * }</pre>
 */
@Target({ElementType.METHOD, ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize("hasAuthority('{value}')")
public @interface RequiresPermission {

  /**
   * The permission string that the authenticated principal must hold.
   *
   * @return the required permission
   */
  String value();
}

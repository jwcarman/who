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
package org.jwcarman.who.examples.webapp;

/** The permission strings the board checks with {@code @RequiresPermission}. */
public final class Permissions {

  public static final String TASK_READ = "task.read";
  public static final String TASK_CREATE = "task.create";
  public static final String TASK_DELETE = "task.delete";
  public static final String MEMBER_MANAGE = "member.manage";

  private Permissions() {}
}

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
package org.jwcarman.who.spring.security.web;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jwcarman.who.spring.security.WhoPaths;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Fails startup when who's controllers would not answer where who's sign-in filters, logout, public
 * paths and error handling expect them: at {@link WhoPaths#of}. A controller mapping declared under
 * {@link WhoPaths#ROOT} must resolve to exactly that path, so a path prefix the application puts on
 * its controllers, a {@code WhoPaths} bean whose root is not {@code who.base-path}, or a {@code
 * spring.mvc.servlet.path} other than {@code /} is refused, naming the mapping.
 */
public final class WhoMappingCheck implements SmartInitializingSingleton {

  private static final int SHOWN = 3;

  private final WhoPaths paths;
  private final ObjectProvider<RequestMappingHandlerMapping> mappings;
  private final String servletPath;

  /**
   * Creates the check.
   *
   * @param paths who's base path
   * @param mappings Spring MVC's annotation handler mappings, read once every bean exists
   * @param servletPath the dispatcher servlet's path, {@code spring.mvc.servlet.path}
   */
  public WhoMappingCheck(
      WhoPaths paths, ObjectProvider<RequestMappingHandlerMapping> mappings, String servletPath) {
    this.paths = requireNonNull(paths, "paths must not be null");
    this.mappings = requireNonNull(mappings, "mappings must not be null");
    this.servletPath = requireNonNull(servletPath, "servletPath must not be null");
  }

  @Override
  public void afterSingletonsInstantiated() {
    List<String> wrong = new ArrayList<>();
    for (RequestMappingHandlerMapping mapping : mappings.orderedStream().toList()) {
      for (Map.Entry<RequestMappingInfo, HandlerMethod> entry :
          mapping.getHandlerMethods().entrySet()) {
        check(entry.getKey(), entry.getValue(), wrong);
      }
    }
    if (wrong.isEmpty()) {
      return;
    }
    boolean servletPathSet = !servletPath.isEmpty() && !"/".equals(servletPath);
    String named =
        wrong.size() <= SHOWN
            ? String.join(", ", wrong)
            : String.join(", ", wrong.subList(0, SHOWN))
                + " and "
                + (wrong.size() - SHOWN)
                + " more";
    throw new IllegalStateException(
        "who's endpoints must answer under who's base path ("
            + paths.root()
            + "), where its sign-in, logout and error handling are, but "
            + named
            + (servletPathSet
                ? ". spring.mvc.servlet.path is " + servletPath + "; who needs it to be /"
                : ". Remove the path prefix on these controllers, or set who.base-path to match"
                    + " the WhoPaths bean"));
  }

  private void check(RequestMappingInfo info, HandlerMethod method, List<String> wrong) {
    RequestMapping declared =
        AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), RequestMapping.class);
    if (declared == null) {
      return;
    }
    for (String path : declared.path()) {
      if (!path.startsWith(WhoPaths.ROOT)) {
        continue;
      }
      String expected = paths.of(path.substring(WhoPaths.ROOT.length()));
      String prefix = servletPathPrefix();
      for (String actual : info.getPatternValues()) {
        String served = prefix + actual;
        if (!served.equals(expected)) {
          wrong.add(method.getShortLogMessage() + " answers at " + served + ", not " + expected);
        }
      }
    }
  }

  private String servletPathPrefix() {
    if (servletPath.isEmpty() || "/".equals(servletPath)) {
      return "";
    }
    String trimmed =
        servletPath.endsWith("/*")
            ? servletPath.substring(0, servletPath.length() - 2)
            : servletPath;
    return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
  }
}

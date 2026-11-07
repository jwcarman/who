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

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.util.AntPathMatcher;

class NativeResourceMetadataTest {

  private static final String METADATA_PATTERN =
      "classpath*:META-INF/native-image/org.jwcarman.who/*/reachability-metadata.json";
  private static final Pattern GLOB = Pattern.compile("\"glob\"\\s*:\\s*\"([^\"]+)\"");

  private final PathMatchingResourcePatternResolver resolver =
      new PathMatchingResourcePatternResolver();

  @Test
  void everyModuleShipsMetadata() throws IOException {
    Set<String> artifacts = new TreeSet<>();
    for (Resource json : resolver.getResources(METADATA_PATTERN)) {
      String path = json.getURL().getPath();
      String[] parts = path.split("/");
      artifacts.add(parts[parts.length - 2]);
    }
    assertThat(artifacts)
        .containsExactlyInAnyOrder(
            "who-jdbc",
            "who-rbac",
            "who-jwt",
            "who-enrollment",
            "who-apikey",
            "who-email-link",
            "who-passkey",
            "who-autoconfigure");
  }

  @Test
  void everyGlobMatchesAFileInItsModule() throws IOException {
    for (Resource json : resolver.getResources(METADATA_PATTERN)) {
      List<String> globs = globsOf(json);
      assertThat(globs).as("globs in %s", json.getURL()).isNotEmpty();
      for (String glob : globs) {
        assertThat(resolver.getResources("classpath*:" + glob))
            .as("glob %s from %s", glob, json.getURL())
            .isNotEmpty();
      }
    }
  }

  @Test
  void eachModuleShipsOnlyItsOwnResources() throws IOException {
    for (Resource json : resolver.getResources(METADATA_PATTERN)) {
      String root = rootOf(json.getURL(), "META-INF/native-image/");
      for (String glob : globsOf(json)) {
        for (Resource match : resolver.getResources("classpath*:" + glob)) {
          assertThat(rootOf(match.getURL(), glob.substring(0, glob.lastIndexOf('/') + 1)))
              .as("glob %s from %s", glob, json.getURL())
              .isEqualTo(root);
        }
      }
    }
  }

  @Test
  void everyWhoChangelogAndSchemaIsCovered() throws IOException {
    List<String> globs = new ArrayList<>();
    for (Resource json : resolver.getResources(METADATA_PATTERN)) {
      globs.addAll(globsOf(json));
    }
    AntPathMatcher matcher = new AntPathMatcher();
    List<Resource> candidates = new ArrayList<>();
    candidates.addAll(List.of(resolver.getResources("classpath*:org/jwcarman/who/**/db/*.xml")));
    candidates.addAll(
        List.of(resolver.getResources("classpath*:org/jwcarman/who/**/who-*-schema.sql")));
    assertThat(candidates).isNotEmpty();
    for (Resource candidate : candidates) {
      String url = candidate.getURL().toString();
      String path = url.substring(url.indexOf("org/jwcarman/who/"));
      assertThat(globs).as("coverage of %s", path).anyMatch(glob -> matcher.match(glob, path));
    }
  }

  private static List<String> globsOf(Resource json) throws IOException {
    String text = new String(json.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    List<String> globs = new ArrayList<>();
    Matcher m = GLOB.matcher(text);
    while (m.find()) {
      globs.add(m.group(1));
    }
    return globs;
  }

  /** The URL up to (not including) the first occurrence of the resource-relative marker. */
  private static String rootOf(URL url, String marker) {
    String text = url.toString();
    int idx = text.lastIndexOf(marker);
    assertThat(idx).as("%s contains %s", text, marker).isNotNegative();
    return text.substring(0, idx);
  }
}

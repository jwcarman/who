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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Copies who's guides and changelog into the agent skill's references, so the skill ships the docs
 * of its own commit. A copy longer than {@value #CONTENTS_THRESHOLD} lines gets a contents list
 * under its title: agents often read only the start of a long file, and the list shows them what
 * the rest holds. A build from the repository root runs this ({@code ./mvnw -N generate-resources}
 * is enough); CI fails when a commit's copies are stale.
 *
 * <p>Usage: {@code java plugins/SyncReferences.java <repository root>}
 */
public final class SyncReferences {

  private static final int CONTENTS_THRESHOLD = 100;
  private static final String CHANGELOG = "CHANGELOG.md";

  private SyncReferences() {}

  public static void main(String[] args) throws IOException {
    if (args.length != 1) {
      System.err.println("usage: java plugins/SyncReferences.java <repository root>");
      System.exit(2);
    }
    Path root = Path.of(args[0]);
    Path target = root.resolve("plugins/who/skills/who/references");
    Map<String, Path> sources = new TreeMap<>();
    try (Stream<Path> guides = Files.list(root.resolve("docs/guides"))) {
      guides
          .filter(p -> p.getFileName().toString().endsWith(".md"))
          .forEach(p -> sources.put(p.getFileName().toString(), p));
    }
    if (sources.keySet().stream().anyMatch(name -> name.equalsIgnoreCase(CHANGELOG))) {
      throw new IllegalStateException("docs/guides must not hold a " + CHANGELOG);
    }
    sources.put(CHANGELOG, root.resolve(CHANGELOG));

    Files.createDirectories(target);
    for (Map.Entry<String, Path> source : sources.entrySet()) {
      Path copy = target.resolve(source.getKey());
      String content = withContents(Files.readAllLines(source.getValue()));
      if (!Files.exists(copy) || !Files.readString(copy).equals(content)) {
        Files.writeString(copy, content);
      }
    }
    try (Stream<Path> copies = Files.list(target)) {
      for (Path copy : copies.toList()) {
        String name = copy.getFileName().toString();
        if (Files.isRegularFile(copy) && name.endsWith(".md") && !sources.containsKey(name)) {
          Files.delete(copy);
        }
      }
    }
  }

  /** The document, with a contents list after its title when it is long. */
  static String withContents(List<String> lines) {
    List<String> sections = new ArrayList<>();
    int title = -1;
    boolean fenced = false;
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i);
      String trimmed = line.strip();
      if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
        fenced = !fenced;
      } else if (!fenced && line.startsWith("## ")) {
        sections.add(line.substring(3).strip());
      } else if (!fenced && title < 0 && line.startsWith("# ")) {
        title = i;
      }
    }
    if (lines.size() <= CONTENTS_THRESHOLD || sections.size() < 2) {
      return String.join("\n", lines) + "\n";
    }
    int insertAt = title + 1;
    List<String> out = new ArrayList<>(lines.subList(0, insertAt));
    out.add("");
    out.add("**Contents**");
    out.add("");
    sections.forEach(section -> out.add("- " + section));
    out.addAll(lines.subList(insertAt, lines.size()));
    return String.join("\n", out) + "\n";
  }
}

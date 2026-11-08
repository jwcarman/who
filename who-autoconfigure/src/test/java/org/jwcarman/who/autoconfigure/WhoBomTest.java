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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

/**
 * The BOM must list every published module and nothing else. Its parent imports Spring Boot's
 * dependency management, so the published pom is the flattened one, which has no parent and must
 * not re-export Spring Boot's entries. The flattened pom is written by who-bom's process-resources,
 * and who-bom is the first module in the reactor, so a full build has it before this test runs.
 */
class WhoBomTest {

  private static final Path MODULE_DIR = Path.of(System.getProperty("user.dir"));
  private static final Path ROOT_POM = MODULE_DIR.resolve("../pom.xml").normalize();
  private static final Path BOM_POM = MODULE_DIR.resolve("../who-bom/pom.xml").normalize();
  private static final Path FLATTENED_BOM_POM =
      MODULE_DIR.resolve("../who-bom/.flattened-pom.xml").normalize();
  private static final Set<String> UNPUBLISHED = Set.of("who-example", "who-bom");

  @Test
  void bomListsEveryPublishedModule() throws Exception {
    Set<String> published = new TreeSet<>(strings(parse(ROOT_POM), "/project/modules/module"));
    published.removeAll(UNPUBLISHED);

    Set<String> managed =
        new TreeSet<>(
            strings(
                parse(BOM_POM),
                "/project/dependencyManagement/dependencies/dependency/artifactId"));

    assertThat(managed).isNotEmpty().isEqualTo(published);
  }

  @Test
  void bomManagesOnlyWhoArtifacts() throws Exception {
    assertThat(FLATTENED_BOM_POM)
        .as("run a full reactor build: who-bom's process-resources writes the flattened pom")
        .exists();
    Document bom = parse(FLATTENED_BOM_POM);

    assertThat(strings(bom, "/project/parent")).isEmpty();
    assertThat(strings(bom, "/project/dependencyManagement/dependencies/dependency/groupId"))
        .isNotEmpty()
        .allMatch("org.jwcarman.who"::equals);
  }

  private static Document parse(Path pom) throws Exception {
    return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom.toFile());
  }

  private static List<String> strings(Document document, String expression) throws Exception {
    XPath xpath = XPathFactory.newInstance().newXPath();
    NodeList nodes = (NodeList) xpath.evaluate(expression, document, XPathConstants.NODESET);
    List<String> values = new ArrayList<>();
    for (int i = 0; i < nodes.getLength(); i++) {
      values.add(nodes.item(i).getTextContent().trim());
    }
    return values;
  }
}

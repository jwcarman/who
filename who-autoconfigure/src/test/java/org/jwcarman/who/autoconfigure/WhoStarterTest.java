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

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

/**
 * The starter brings who's base: identities, sessions and permissions. Each sign-in method is a
 * dependency the application adds, and adding it turns the method on.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class WhoStarterTest {

  private static final Path STARTER_POM =
      Path.of(System.getProperty("user.dir"))
          .resolve("../who-spring-boot-starter/pom.xml")
          .normalize();

  @Test
  void the_starter_brings_no_sign_in_method() throws Exception {
    Document pom =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(STARTER_POM.toFile());
    XPath xpath = XPathFactory.newInstance().newXPath();
    NodeList nodes =
        (NodeList)
            xpath.evaluate(
                "/project/dependencies/dependency/artifactId", pom, XPathConstants.NODESET);
    List<String> artifacts = new ArrayList<>();
    for (int i = 0; i < nodes.getLength(); i++) {
      artifacts.add(nodes.item(i).getTextContent().trim());
    }

    assertThat(new TreeSet<>(artifacts))
        .isEqualTo(
            new TreeSet<>(
                Set.of(
                    "who-autoconfigure",
                    "who-core",
                    "who-spring-security",
                    "who-jdbc",
                    "who-rbac")));
  }
}

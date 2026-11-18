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
package org.jwcarman.who.email;

import static java.util.Objects.requireNonNull;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.jwcarman.who.core.email.OutgoingEmail;
import org.jwcarman.who.core.email.WhoEmail;
import org.jwcarman.who.core.email.WhoEmailRenderer;
import org.springframework.context.MessageSource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.MustacheException;
import com.samskivert.mustache.Template;

/**
 * Renders who's emails from Mustache templates.
 *
 * <p>An email whose {@link WhoEmail#template()} is {@code sign-in} renders {@code sign-in.txt} as
 * its text and, when it exists, {@code sign-in.html} as its HTML. Each file is looked for in the
 * given template locations, in order, then among who's built-in templates at {@link
 * #BUILT_IN_LOCATION}; the first that has it wins, so an application or a library replaces one file
 * without touching the others. Partials ({@code {{> footer}}}) are found the same way, with the
 * including template's extension.
 *
 * <p>A template sees three names: {@code email}, the email record itself; {@code app}, the
 * application-wide values; and {@code extra}, the values from {@link WhoEmailModelContributor}s. A
 * variable the model lacks fails the render; a value that is null renders empty. Text templates are
 * not escaped; HTML templates escape their values.
 *
 * <p>The subject is the message {@code who.email.<template>.subject} in the email's locale.
 *
 * <p>Every template in every location is compiled when the renderer is created, so a template that
 * does not compile fails startup rather than the first send.
 */
public final class MustacheWhoEmailRenderer implements WhoEmailRenderer {

  /** Where feature modules put their built-in templates, on the classpath. */
  public static final String BUILT_IN_LOCATION = "classpath:/org/jwcarman/who/email/templates/";

  private static final String BUILT_IN_PATTERN = "classpath*:/org/jwcarman/who/email/templates/";
  private static final String TEXT = ".txt";
  private static final String HTML = ".html";

  private final ResourcePatternResolver resources;
  private final List<String> locations;
  private final MessageSource messages;
  private final Map<String, String> app;
  private final List<WhoEmailModelContributor> contributors;
  private final Mustache.Compiler textCompiler;
  private final Mustache.Compiler htmlCompiler;
  private final Map<String, Optional<Template>> compiled = new ConcurrentHashMap<>();

  /**
   * Creates the renderer and compiles every template it can find.
   *
   * @param resources finds the templates
   * @param templateLocations the locations searched before who's own, in order: the application's,
   *     then the libraries'; a missing trailing {@code /} is added
   * @param messages supplies the subjects
   * @param app the application-wide values, seen as {@code app}
   * @param contributors supply the values seen as {@code extra}, in order
   * @throws IllegalStateException if a template does not compile
   */
  public MustacheWhoEmailRenderer(
      ResourcePatternResolver resources,
      List<String> templateLocations,
      MessageSource messages,
      Map<String, String> app,
      List<WhoEmailModelContributor> contributors) {
    this.resources = requireNonNull(resources, "resources must not be null");
    requireNonNull(templateLocations, "templateLocations must not be null");
    List<String> given =
        templateLocations.stream()
            .map(location -> location.endsWith("/") ? location : location + "/")
            .toList();
    this.locations = Stream.concat(given.stream(), Stream.of(BUILT_IN_LOCATION)).toList();
    this.messages = requireNonNull(messages, "messages must not be null");
    this.app = Map.copyOf(requireNonNull(app, "app must not be null"));
    this.contributors = List.copyOf(requireNonNull(contributors, "contributors must not be null"));
    // a text email is sent as text/plain, where escaping would only corrupt it ("&b=2" in a link);
    // HTML templates escape their values
    this.textCompiler = compiler(TEXT).escapeHTML(false);
    this.htmlCompiler = compiler(HTML);
    given.forEach(this::verify);
    verify(BUILT_IN_PATTERN);
  }

  @Override
  public OutgoingEmail render(WhoEmail email, Locale locale) {
    String name = email.template();
    Template text =
        template(name + TEXT)
            .orElseThrow(
                () -> new IllegalStateException("No who email template named " + name + TEXT));
    Map<String, Object> model = model(email);
    String html = template(name + HTML).map(template -> template.execute(model)).orElse(null);
    String subject = messages.getMessage("who.email." + name + ".subject", null, locale);
    return new OutgoingEmail(email.address(), subject, text.execute(model), html, email);
  }

  private Map<String, Object> model(WhoEmail email) {
    Map<String, Object> extra = new HashMap<>();
    contributors.forEach(contributor -> extra.putAll(contributor.extra(email)));
    return Map.of("email", email, "app", app, "extra", extra);
  }

  private Optional<Template> template(String fileName) {
    return compiled.computeIfAbsent(
        fileName, name -> find(name).map(resource -> compile(resource, name)));
  }

  private Mustache.Compiler compiler(String extension) {
    return Mustache.compiler()
        .nullValue("")
        .withLoader(
            partial ->
                find(partial + extension)
                    .map(MustacheWhoEmailRenderer::reader)
                    .orElseThrow(
                        () ->
                            new IllegalStateException(
                                "No who email partial named " + partial + extension)));
  }

  private Optional<Resource> find(String fileName) {
    return locations.stream()
        .map(location -> resources.getResource(location + fileName))
        .filter(Resource::exists)
        .findFirst();
  }

  private Template compile(Resource resource, String fileName) {
    Mustache.Compiler compiler = fileName.endsWith(HTML) ? htmlCompiler : textCompiler;
    try (Reader reader = reader(resource)) {
      return compiler.compile(reader);
    } catch (MustacheException | IOException e) {
      throw new IllegalStateException(
          "The who email template " + resource.getDescription() + " does not compile", e);
    }
  }

  private void verify(String location) {
    for (String extension : List.of(TEXT, HTML)) {
      try {
        for (Resource resource : resources.getResources(location + "*" + extension)) {
          compile(resource, requireNonNull(resource.getFilename(), "filename"));
        }
      } catch (FileNotFoundException _) {
        // no such location: an application without templates of its own
      } catch (IOException e) {
        throw new UncheckedIOException("Could not list the who email templates in " + location, e);
      }
    }
  }

  private static Reader reader(Resource resource) {
    try {
      return new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Could not read " + resource.getDescription(), e);
    }
  }
}

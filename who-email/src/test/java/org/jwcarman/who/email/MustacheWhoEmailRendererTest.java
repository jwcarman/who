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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.email.OutgoingEmail;
import org.jwcarman.who.core.email.WhoEmail;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class MustacheWhoEmailRendererTest {

  record TestEmail(String address, String template, String name, @Nullable String link)
      implements WhoEmail {}

  private static final String LINK = "https://x/?a=1&b=2";
  private static final String APP_LOCATION = "classpath:/app-templates/";
  private static final String EMPTY_LOCATION = "classpath:/no-such-templates/";
  private static final String LIB = "classpath:/lib-templates/";
  private static final String LIB2 = "classpath:/lib2-templates/";

  private final StaticMessageSource messages = messages();

  private static StaticMessageSource messages() {
    StaticMessageSource source = new StaticMessageSource();
    for (String template :
        List.of(
            "greeting",
            "farewell",
            "nope",
            "nullish",
            "partial",
            "extra",
            "absent",
            "withfooter")) {
      source.addMessage("who.email." + template + ".subject", Locale.ENGLISH, "About " + template);
    }
    source.addMessage("who.email.greeting.subject", Locale.FRENCH, "Bonjour");
    return source;
  }

  private MustacheWhoEmailRenderer renderer(String location) {
    return renderer(location, List.of());
  }

  private MustacheWhoEmailRenderer renderer(
      String location, List<WhoEmailModelContributor> contributors) {
    return renderer(List.of(location), contributors);
  }

  private MustacheWhoEmailRenderer renderer(List<String> locations) {
    return renderer(locations, List.of());
  }

  private MustacheWhoEmailRenderer renderer(
      List<String> locations, List<WhoEmailModelContributor> contributors) {
    return new MustacheWhoEmailRenderer(
        new PathMatchingResourcePatternResolver(),
        locations,
        messages,
        Map.of("name", "Roux"),
        contributors);
  }

  private static TestEmail email(String template) {
    return new TestEmail("ann@example.com", template, "Ann", LINK);
  }

  @Nested
  class Templates {

    @Test
    void renders_the_built_in_text_with_email_and_app_values() {
      OutgoingEmail out = renderer(EMPTY_LOCATION).render(email("greeting"), Locale.ENGLISH);

      assertThat(out.to()).isEqualTo("ann@example.com");
      assertThat(out.text()).isEqualTo("Hello Ann from Roux: https://x/?a=1&b=2");
    }

    @Test
    void an_override_of_one_template_leaves_the_others_built_in() {
      MustacheWhoEmailRenderer renderer = renderer(APP_LOCATION);

      assertThat(renderer.render(email("farewell"), Locale.ENGLISH).text())
          .isEqualTo("See you Ann");
      assertThat(renderer.render(email("greeting"), Locale.ENGLISH).text())
          .isEqualTo("Hello Ann from Roux: https://x/?a=1&b=2");
    }

    @Test
    void an_html_override_keeps_the_built_in_text() {
      OutgoingEmail out = renderer(APP_LOCATION).render(email("greeting"), Locale.ENGLISH);

      assertThat(out.html()).isEqualTo("<p>https://x/?a&#x3D;1&amp;b&#x3D;2</p>");
      assertThat(out.text()).isEqualTo("Hello Ann from Roux: https://x/?a=1&b=2");
    }

    @Test
    void without_an_html_template_html_is_null() {
      assertThat(renderer(APP_LOCATION).render(email("farewell"), Locale.ENGLISH).html()).isNull();
    }

    @Test
    void a_missing_variable_fails() {
      MustacheWhoEmailRenderer renderer = renderer(EMPTY_LOCATION);

      assertThatThrownBy(() -> renderer.render(email("nope"), Locale.ENGLISH))
          .hasMessageContaining("nope");
    }

    @Test
    void a_null_value_renders_empty() {
      TestEmail email = new TestEmail("ann@example.com", "nullish", "Ann", null);

      assertThat(renderer(EMPTY_LOCATION).render(email, Locale.ENGLISH).text()).isEqualTo("[]");
    }

    @Test
    void a_partial_comes_from_the_app_location_first() {
      assertThat(renderer(APP_LOCATION).render(email("partial"), Locale.ENGLISH).text())
          .isEqualTo("Body app footer");
      assertThat(renderer(EMPTY_LOCATION).render(email("partial"), Locale.ENGLISH).text())
          .isEqualTo("Body built-in footer");
    }

    @Test
    void a_missing_text_template_fails_with_its_name() {
      MustacheWhoEmailRenderer renderer = renderer(EMPTY_LOCATION);
      WhoEmail absent = email("absent");

      assertThatThrownBy(() -> renderer.render(absent, Locale.ENGLISH))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("absent.txt");
    }

    @Test
    void a_template_that_does_not_compile_fails_construction() {
      assertThatThrownBy(() -> renderer("classpath:/broken-templates/"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("bad.txt");
    }
  }

  @Nested
  class Locations {

    @Test
    void a_library_template_beats_the_built_in() {
      assertThat(
              renderer(List.of(EMPTY_LOCATION, LIB))
                  .render(email("greeting"), Locale.ENGLISH)
                  .text())
          .isEqualTo("Library hello Ann");
    }

    @Test
    void the_application_beats_a_library() {
      assertThat(
              renderer(List.of(APP_LOCATION, LIB)).render(email("farewell"), Locale.ENGLISH).text())
          .isEqualTo("See you Ann");
    }

    @Test
    void the_first_library_wins() {
      assertThat(
              renderer(List.of(EMPTY_LOCATION, LIB2, LIB))
                  .render(email("greeting"), Locale.ENGLISH)
                  .text())
          .isEqualTo("Second library hello");
    }

    @Test
    void a_partial_comes_from_a_library_when_the_application_lacks_it() {
      assertThat(
              renderer(List.of(APP_LOCATION, LIB))
                  .render(email("withfooter"), Locale.ENGLISH)
                  .text())
          .isEqualTo("Body and library footer");
    }

    @Test
    void a_missing_library_location_is_skipped() {
      assertThat(
              renderer(List.of(EMPTY_LOCATION, "classpath:/no-such-library/"))
                  .render(email("greeting"), Locale.ENGLISH)
                  .text())
          .isEqualTo("Hello Ann from Roux: https://x/?a=1&b=2");
    }

    @Test
    void a_library_template_that_does_not_compile_fails_creation() {
      List<String> locations = List.of(EMPTY_LOCATION, "classpath:/broken-lib-templates/");

      assertThatThrownBy(() -> renderer(locations))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("bad.txt");
    }

    @Test
    void a_location_without_a_trailing_slash_is_found() {
      assertThat(
              renderer(List.of(EMPTY_LOCATION, "classpath:/lib-templates"))
                  .render(email("greeting"), Locale.ENGLISH)
                  .text())
          .isEqualTo("Library hello Ann");
    }
  }

  @Nested
  class Subjects {

    @Test
    void the_subject_comes_from_the_message_source_in_the_locale() {
      MustacheWhoEmailRenderer renderer = renderer(EMPTY_LOCATION);

      assertThat(renderer.render(email("greeting"), Locale.ENGLISH).subject())
          .isEqualTo("About greeting");
      assertThat(renderer.render(email("greeting"), Locale.FRENCH).subject()).isEqualTo("Bonjour");
    }

    private OutgoingEmail render(
        TestEmail email, String subject, List<WhoEmailModelContributor> contributors) {
      StaticMessageSource source = new StaticMessageSource();
      source.addMessage("who.email." + email.template() + ".subject", Locale.ENGLISH, subject);
      return new MustacheWhoEmailRenderer(
              new PathMatchingResourcePatternResolver(),
              List.of(EMPTY_LOCATION),
              source,
              Map.of("name", "Roux"),
              contributors)
          .render(email, Locale.ENGLISH);
    }

    @Test
    void a_subject_names_the_app() {
      assertThat(render(email("greeting"), "Sign in to {{app.name}}", List.of()).subject())
          .isEqualTo("Sign in to Roux");
    }

    @Test
    void a_subject_sees_the_email_and_extra() {
      List<WhoEmailModelContributor> contributors = List.of(email -> Map.of("brand", "Acme"));

      assertThat(
              render(email("greeting"), "{{extra.brand}} for {{email.name}}", contributors)
                  .subject())
          .isEqualTo("Acme for Ann");
    }

    @Test
    void a_plain_subject_is_unchanged() {
      assertThat(render(email("greeting"), "It's {0} & <b>", List.of()).subject())
          .isEqualTo("It's {0} & <b>");
    }

    @Test
    void a_line_break_in_a_value_becomes_a_space() {
      TestEmail email = new TestEmail("ann@example.com", "greeting", "Ann\r\n\r\nBcc: x", LINK);

      assertThat(render(email, "Hi {{email.name}}", List.of()).subject())
          .isEqualTo("Hi Ann Bcc: x");
    }

    @Test
    void a_subject_naming_a_missing_value_fails_the_render() {
      TestEmail email = email("greeting");

      assertThatThrownBy(() -> render(email, "Hi {{nope}}", List.of()))
          .hasMessageContaining("nope");
    }

    @Test
    void a_subject_that_does_not_compile_fails_creation() {
      StaticMessageSource source = new StaticMessageSource();
      source.addMessage("who.email.greeting.subject", Locale.ROOT, "Hi {{#open}}");
      PathMatchingResourcePatternResolver resources = new PathMatchingResourcePatternResolver();
      List<String> locations = List.of(EMPTY_LOCATION);
      Map<String, String> app = Map.of();
      List<WhoEmailModelContributor> contributors = List.of();

      assertThatThrownBy(
              () -> new MustacheWhoEmailRenderer(resources, locations, source, app, contributors))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("who.email.greeting.subject");
    }
  }

  @Nested
  class Extra {

    @Test
    void contributors_fill_extra() {
      List<WhoEmailModelContributor> contributors =
          List.of(
              email -> Map.of("nickname", "Annie", "team", "red"), email -> Map.of("team", "blue"));

      assertThat(
              renderer(EMPTY_LOCATION, contributors).render(email("extra"), Locale.ENGLISH).text())
          .isEqualTo("Annie blue");
    }
  }
}

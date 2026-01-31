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
package org.jwcarman.who.core.service.impl;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JsonPreferencesMergerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonPreferencesMerger merger = new JsonPreferencesMerger();

    @Test
    void testSimpleMerge() throws Exception {
        JsonNode layer1 = mapper.readTree("{\"a\": 1, \"b\": 2}");
        JsonNode layer2 = mapper.readTree("{\"b\": 3, \"c\": 4}");

        JsonNode result = merger.merge(layer1, layer2);

        assertThat(result.get("a").asInt()).isEqualTo(1);
        assertThat(result.get("b").asInt()).isEqualTo(3);
        assertThat(result.get("c").asInt()).isEqualTo(4);
    }

    @Test
    void testNullHandling() throws Exception {
        JsonNode layer1 = mapper.readTree("{\"a\": 1, \"b\": 2}");
        JsonNode layer2 = mapper.readTree("{\"b\": null, \"c\": 3}");

        JsonNode result = merger.merge(layer1, layer2);

        assertThat(result.get("a").asInt()).isEqualTo(1);
        assertThat(result.get("b").asInt()).isEqualTo(2);
        assertThat(result.get("c").asInt()).isEqualTo(3);
    }

    @Test
    void testMultipleLayers() throws Exception {
        JsonNode layer1 = mapper.readTree("{\"a\": 1, \"nested\": {\"x\": 10}}");
        JsonNode layer2 = mapper.readTree("{\"b\": 2, \"nested\": {\"y\": 20}}");
        JsonNode layer3 = mapper.readTree("{\"c\": 3, \"nested\": {\"x\": 30}}");

        JsonNode result = merger.merge(layer1, layer2, layer3);

        assertThat(result.get("a").asInt()).isEqualTo(1);
        assertThat(result.get("b").asInt()).isEqualTo(2);
        assertThat(result.get("c").asInt()).isEqualTo(3);
        assertThat(result.get("nested").get("x").asInt()).isEqualTo(30);
        assertThat(result.get("nested").get("y").asInt()).isEqualTo(20);
    }
}

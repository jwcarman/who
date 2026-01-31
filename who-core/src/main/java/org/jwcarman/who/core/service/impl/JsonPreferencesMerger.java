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
import tools.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Map;

public class JsonPreferencesMerger {

    @SafeVarargs
    public final JsonNode merge(JsonNode... layers) {
        if (layers.length == 0) {
            return null;
        }

        ObjectNode result = (ObjectNode) layers[0].deepCopy();
        for (int i = 1; i < layers.length; i++) {
            deepMerge(result, layers[i]);
        }
        return result;
    }

    private void deepMerge(ObjectNode target, JsonNode source) {
        if (source == null || !source.isObject()) {
            return;
        }

        Iterator<Map.Entry<String, JsonNode>> fields = ((ObjectNode) source).properties().iterator();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String fieldName = entry.getKey();
            JsonNode sourceValue = entry.getValue();

            // Skip null values
            if (sourceValue.isNull()) {
                continue;
            }

            JsonNode targetValue = target.get(fieldName);

            // If both are objects, merge recursively
            if (targetValue != null && targetValue.isObject() && sourceValue.isObject()) {
                deepMerge((ObjectNode) targetValue, sourceValue);
            } else {
                // Otherwise, replace the value
                target.set(fieldName, sourceValue.deepCopy());
            }
        }
    }
}

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

/**
 * Utility for merging multiple JSON preference layers into a single result.
 * <p>
 * This merger implements a deep merge strategy where:
 * <ul>
 *   <li>Later layers override earlier layers</li>
 *   <li>Nested objects are merged recursively</li>
 *   <li>Null values in later layers are ignored (they don't override earlier values)</li>
 *   <li>Non-object values in later layers completely replace earlier values</li>
 * </ul>
 * <p>
 * This is useful for implementing preference hierarchies where user preferences can
 * override default preferences, with fine-grained control at the field level.
 * <p>
 * Example:
 * <pre>{@code
 * Layer 1 (defaults): {"theme": {"color": "blue", "size": "medium"}}
 * Layer 2 (user):     {"theme": {"color": "red"}}
 * Result:             {"theme": {"color": "red", "size": "medium"}}
 * }</pre>
 */
public class JsonPreferencesMerger {

    /**
     * Merges multiple JSON layers from left to right, with later layers overriding earlier ones.
     * <p>
     * The first layer serves as the base, and each subsequent layer is merged into the result,
     * overriding values from previous layers. Nested objects are merged recursively to preserve
     * fields that are not explicitly overridden.
     *
     * @param layers the JSON layers to merge, ordered from lowest to highest priority
     * @return the merged JSON result, or {@code null} if no layers are provided
     */
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

    /**
     * Recursively merges source JSON into target JSON.
     * <p>
     * For each field in the source:
     * <ul>
     *   <li>If the value is null, it is skipped (doesn't override target)</li>
     *   <li>If both target and source values are objects, merge them recursively</li>
     *   <li>Otherwise, the source value replaces the target value</li>
     * </ul>
     *
     * @param target the target object to merge into (modified in place)
     * @param source the source object to merge from
     */
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

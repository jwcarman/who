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

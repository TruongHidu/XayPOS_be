package com.possaas.modules.subscription.dto;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** Preserve exact JSON numbers; business validation is performed by PackageLimitPolicy. */
public class PackageLimitDeserializer extends ValueDeserializer<Object> {
    @Override
    public Object deserialize(JsonParser parser, DeserializationContext context) {
        return parser.currentToken().isNumeric()
            ? parser.getDecimalValue()
            : parser.readValueAs(Object.class);
    }
}

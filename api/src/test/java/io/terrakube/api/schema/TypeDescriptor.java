package io.terrakube.api.schema;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;

/**
 * The type of one attribute's values in the exported model. Serialises with kind first, so a consumer can switch on it
 * before reading anything else.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = TypeDescriptor.Str.class, name = "string"),
        @JsonSubTypes.Type(value = TypeDescriptor.Num.class, name = "number"),
        @JsonSubTypes.Type(value = TypeDescriptor.Bool.class, name = "boolean"),
        @JsonSubTypes.Type(value = TypeDescriptor.Unknown.class, name = "unknown"),
        @JsonSubTypes.Type(value = TypeDescriptor.EnumOf.class, name = "enum"),
        @JsonSubTypes.Type(value = TypeDescriptor.ArrayOf.class, name = "array"),
        @JsonSubTypes.Type(value = TypeDescriptor.MapOf.class, name = "map"),
        @JsonSubTypes.Type(value = TypeDescriptor.DateTime.class, name = "datetime"),
})
public sealed interface TypeDescriptor
        permits TypeDescriptor.Str, TypeDescriptor.Num, TypeDescriptor.Bool, TypeDescriptor.Unknown,
        TypeDescriptor.EnumOf, TypeDescriptor.ArrayOf, TypeDescriptor.MapOf, TypeDescriptor.DateTime {

    record Str() implements TypeDescriptor {
    }

    record Num() implements TypeDescriptor {
    }

    record Bool() implements TypeDescriptor {
    }

    record Unknown() implements TypeDescriptor {
    }

    /** name is set only for the API's own top-level enums; without it the descriptor is an inline union. */
    record EnumOf(String name, List<String> values) implements TypeDescriptor {
    }

    record ArrayOf(TypeDescriptor items) implements TypeDescriptor {
    }

    record MapOf(TypeDescriptor values) implements TypeDescriptor {
    }

    record DateTime(String format) implements TypeDescriptor {
    }
}

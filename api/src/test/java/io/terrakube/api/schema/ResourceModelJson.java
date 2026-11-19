package io.terrakube.api.schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.PrettyPrinter;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * The one writer for the exported model, so every consumer sees the same bytes: 2-space indentation, LF line endings,
 * no space before a colon, empty containers inline, nulls omitted and a trailing newline.
 */
public final class ResourceModelJson {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .build();

    private ResourceModelJson() {
    }

    public static String write(ResourceModel model) {
        try {
            return MAPPER.writer(prettyPrinter()).writeValueAsString(model) + "\n";
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("resource model does not serialise", e);
        }
    }

    private static PrettyPrinter prettyPrinter() {
        DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
        Separators separators = Separators.createDefaultInstance()
                .withObjectFieldValueSpacing(Separators.Spacing.AFTER)
                // the default empty separator is a single space, which would print { } and [ ]
                .withObjectEmptySeparator("")
                .withArrayEmptySeparator("");
        return new DefaultPrettyPrinter(separators)
                .withArrayIndenter(indenter)
                .withObjectIndenter(indenter);
    }
}

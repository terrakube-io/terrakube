package io.terrakube.api.rs;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.regex.Pattern;

/**
 * Stores a version's deprecation message without control or format characters. The text can end up
 * in a terminal, where C0/C1 controls (e.g. ESC, U+009B) start escape sequences and bidi overrides
 * (e.g. U+202E) reorder what is displayed. An empty result is stored as null.
 */
@Converter
public class DeprecationMessageConverter implements AttributeConverter<String, String> {

    private static final Pattern CONTROL_OR_FORMAT = Pattern.compile("[\\p{Cc}\\p{Cf}]");

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null) {
            return null;
        }
        String clean = CONTROL_OR_FORMAT.matcher(attribute).replaceAll(" ").strip();
        return clean.isEmpty() ? null : clean;
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return dbData;
    }
}

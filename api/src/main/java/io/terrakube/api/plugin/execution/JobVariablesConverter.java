package io.terrakube.api.plugin.execution;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component
@Converter
@RequiredArgsConstructor
public class JobVariablesConverter implements AttributeConverter<Map<String, String>, String> {
    private final JobVariablesService variables;

    @Override
    public String convertToDatabaseColumn(Map<String, String> values) {
        return variables.encrypt(values);
    }

    @Override
    public Map<String, String> convertToEntityAttribute(String encrypted) {
        return variables.decrypt(encrypted);
    }
}

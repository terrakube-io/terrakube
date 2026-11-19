package io.terrakube.api.schema;

import java.util.Optional;
import java.util.SortedMap;

/** The wire format Elide's serde gives a date/time class, and the definitions those ids refer to. */
public interface DateFormats {

    Optional<String> formatId(Class<?> javaType);

    SortedMap<String, DateFormat> definitions();
}

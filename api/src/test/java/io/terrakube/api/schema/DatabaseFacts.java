package io.terrakube.api.schema;

/**
 * What the database says about a property's columns. True only when every column the property owns is NOT NULL; false
 * for inverse sides, transient members and members with no column.
 */
public interface DatabaseFacts {

    boolean columnNotNull(Class<?> entity, String property);
}

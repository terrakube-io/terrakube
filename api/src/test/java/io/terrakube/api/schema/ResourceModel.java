package io.terrakube.api.schema;

import java.util.List;
import java.util.SortedMap;

/**
 * The API's resources in the shape the UI's generated JSON:API types are built from, plus the date wire formats it
 * references and anything the exporter could not resolve. Record component order is the JSON property order.
 */
public record ResourceModel(SortedMap<String, DateFormat> formats, List<TypeModel> types, List<String> problems) {

    public record TypeModel(
            String name, boolean root, List<AttributeModel> attributes, List<RelationshipModel> relationships) {
    }

    public record AttributeModel(String name, TypeDescriptor type, boolean nullable, boolean optional) {
    }

    public record RelationshipModel(String name, String target, boolean many, boolean required) {
    }
}

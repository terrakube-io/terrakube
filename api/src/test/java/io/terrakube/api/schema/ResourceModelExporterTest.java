package io.terrakube.api.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.yahoo.elide.core.dictionary.EntityDictionary;
import io.terrakube.api.schema.fixture.BrokenCtor;
import io.terrakube.api.schema.fixture.Widget;
import io.terrakube.api.schema.fixture.WidgetDetail;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class ResourceModelExporterTest {

    private static final String RFC3339_SECONDS_UTC = "rfc3339-seconds-utc";

    /**
     * The exact bytes the writer must produce: 2-space indentation, LF line endings, no space before a colon, each
     * descriptor's kind first, a null enum name and a null relationship target omitted, and a trailing newline.
     */
    private static final String EXPECTED_JSON = """
            {
              "formats": {
                "rfc3339-seconds-utc": {
                  "standard": "rfc3339",
                  "precision": "second",
                  "offset": "utc-z",
                  "example": "2026-11-19T15:28:21Z",
                  "description": "Second precision UTC."
                }
              },
              "types": [
                {
                  "name": "widget",
                  "root": true,
                  "attributes": [
                    {
                      "name": "kind",
                      "type": {
                        "kind": "enum",
                        "values": [
                          "MEDIUM",
                          "LOW"
                        ]
                      },
                      "nullable": true,
                      "optional": true
                    },
                    {
                      "name": "state",
                      "type": {
                        "kind": "enum",
                        "name": "WidgetState",
                        "values": [
                          "ON"
                        ]
                      },
                      "nullable": false,
                      "optional": false
                    },
                    {
                      "name": "when",
                      "type": {
                        "kind": "datetime",
                        "format": "rfc3339-seconds-utc"
                      },
                      "nullable": true,
                      "optional": false
                    }
                  ],
                  "relationships": [
                    {
                      "name": "missing",
                      "many": true,
                      "required": false
                    },
                    {
                      "name": "owner",
                      "target": "widget",
                      "many": false,
                      "required": true
                    }
                  ]
                }
              ],
              "problems": [
                "widget.missing: no JSON type for Java type Object, emitted as unknown"
              ]
            }
            """;

    @Test
    void toOneIsSingleAndToManyIsMany() {
        ResourceModel model = export((entity, property) -> false);

        ResourceModel.TypeModel widget = type(model, "widget");
        assertThat(relationship(widget, "parent").many()).isFalse();
        assertThat(relationship(widget, "children").many()).isTrue();
        assertThat(relationship(widget, "parent").target()).isEqualTo("widget");
        assertThat(relationship(widget, "children").target()).isEqualTo("widget");
    }

    @Test
    void rootLevelFalseIsNotRoot() {
        ResourceModel model = export((entity, property) -> false);

        assertThat(type(model, "widget").root()).isTrue();
        assertThat(type(model, "widgetDetail").root()).isFalse();
    }

    @Test
    void inverseOneToOneIsNeverRequired() {
        ResourceModel model = export((entity, property) -> property.equals("widget"));

        assertThat(relationship(type(model, "widgetDetail"), "widget").required()).isFalse();
    }

    @Test
    void notNullToOneIsRequired() {
        ResourceModel model = export((entity, property) -> property.equals("parent"));

        assertThat(relationship(type(model, "widget"), "owner").required()).isTrue();
        assertThat(relationship(type(model, "widget"), "parent").required()).isTrue();
    }

    @Test
    void readRestrictedNotNullIsOptionalNonNull() {
        ResourceModel model = export((entity, property) -> false);

        ResourceModel.AttributeModel secret = attribute(type(model, "widget"), "secret");
        assertThat(secret.optional()).isTrue();
        assertThat(secret.nullable()).isFalse();
    }

    @Test
    void readRestrictedNotNullColumnStaysNullable() {
        ResourceModel model = export((entity, property) -> property.equals("hiddenNote"));

        ResourceModel.AttributeModel hidden = attribute(type(model, "widget"), "hiddenNote");
        assertThat(hidden.nullable()).isTrue();
        assertThat(hidden.optional()).isTrue();
    }

    @Test
    void inheritedDateUsesSuperclassMember() {
        ResourceModel model = export((entity, property) -> false);

        ResourceModel.AttributeModel created = attribute(type(model, "widget"), "createdDate");
        assertThat(created.type()).isEqualTo(new TypeDescriptor.DateTime(RFC3339_SECONDS_UTC));
        assertThat(created.nullable()).isTrue();
        assertThat(model.formats().keySet()).containsExactly(RFC3339_SECONDS_UTC);
    }

    @Test
    void unknownDateClassIsAProblem() {
        ResourceModel model = export((entity, property) -> false);

        ResourceModel.AttributeModel mystery = attribute(type(model, "widget"), "mysteryDate");
        assertThat(mystery.type()).isEqualTo(new TypeDescriptor.Str());
        assertThat(model.problems()).contains("widget.mysteryDate: no known wire format for Java type Year");
    }

    @Test
    void primitiveAndInitialisedAreNonNull() {
        ResourceModel model = export((entity, property) -> false);

        ResourceModel.TypeModel widget = type(model, "widget");
        assertThat(attribute(widget, "retries").nullable()).isFalse();
        assertThat(attribute(widget, "label").nullable()).isFalse();
        assertThat(attribute(widget, "description").nullable()).isTrue();
        assertThat(attribute(widget, "description").optional()).isFalse();
    }

    @Test
    void enumIsNamedAndOrdered() {
        ResourceModel model = export((entity, property) -> false);

        ResourceModel.AttributeModel kind = attribute(type(model, "widget"), "kind");
        assertThat(kind.type()).isEqualTo(new TypeDescriptor.EnumOf("ExampleKind", List.of("MEDIUM", "LOW")));
    }

    @Test
    void mapAndStringListDescriptors() {
        ResourceModel model = export((entity, property) -> false);

        ResourceModel.TypeModel widget = type(model, "widget");
        assertThat(attribute(widget, "labels").type()).isEqualTo(new TypeDescriptor.MapOf(new TypeDescriptor.Str()));
        assertThat(attribute(widget, "tags").type()).isEqualTo(new TypeDescriptor.ArrayOf(new TypeDescriptor.Str()));
    }

    @Test
    void unknownTypeIsReported() {
        ResourceModel model = export((entity, property) -> false);

        assertThat(attribute(type(model, "widget"), "payload").type()).isEqualTo(new TypeDescriptor.Unknown());
        assertThat(model.problems())
                .contains("widget.payload: no JSON type for Java type Object, emitted as unknown");
    }

    @Test
    void collectionElementProblemsCarryTheLocation() {
        ResourceModel model = export((entity, property) -> false);

        assertThat(attribute(type(model, "widget"), "mysteryDates").type())
                .isEqualTo(new TypeDescriptor.ArrayOf(new TypeDescriptor.Str()));
        assertThat(model.problems()).contains("widget.mysteryDates: no known wire format for Java type Year");
    }

    @Test
    void missingFormatDefinitionIsAProblem() {
        ResourceModelExporter exporter = new ResourceModelExporter(dictionary(), (entity, property) -> false,
                new DateFormats() {
                    @Override
                    public Optional<String> formatId(Class<?> javaType) {
                        return javaType == Date.class ? Optional.of("missing-id") : Optional.empty();
                    }

                    @Override
                    public SortedMap<String, DateFormat> definitions() {
                        return new TreeMap<>();
                    }
                });

        ResourceModel model = exporter.export();

        assertThat(model.formats()).isEmpty();
        assertThat(model.problems()).contains("missing-id: used by a datetime but has no format definition");
    }

    @Test
    void failedConstructionIsAProblem() {
        ResourceModel model = export((entity, property) -> false);

        // the constructor failure must not silently mark the initialised field nullable
        assertThat(attribute(type(model, "brokenCtor"), "initialised").nullable()).isTrue();
        assertThat(model.problems())
                .contains("brokenCtor: no-arg construction failed (java.lang.IllegalStateException: boom)");
    }

    @Test
    void exportIsRepeatable() {
        ResourceModelExporter exporter = new ResourceModelExporter(dictionary(), (entity, property) -> false,
                dateFormats());

        assertThat(exporter.export()).isEqualTo(exporter.export());
    }

    @Test
    void writerRendersEmptyContainersInline() {
        String json = ResourceModelJson.write(new ResourceModel(new TreeMap<>(), List.of(), List.of()));

        assertThat(json).isEqualTo("{\n  \"formats\": {},\n  \"types\": [],\n  \"problems\": []\n}\n");
    }

    @Test
    void writerIsStableAndOrdered() {
        ResourceModel model = new ResourceModel(
                formats(RFC3339_SECONDS_UTC),
                List.of(new ResourceModel.TypeModel(
                        "widget",
                        true,
                        List.of(
                                new ResourceModel.AttributeModel(
                                        "kind", new TypeDescriptor.EnumOf(null, List.of("MEDIUM", "LOW")), true, true),
                                new ResourceModel.AttributeModel(
                                        "state",
                                        new TypeDescriptor.EnumOf("WidgetState", List.of("ON")),
                                        false,
                                        false),
                                new ResourceModel.AttributeModel(
                                        "when", new TypeDescriptor.DateTime(RFC3339_SECONDS_UTC), true, false)),
                        List.of(
                                new ResourceModel.RelationshipModel("missing", null, true, false),
                                new ResourceModel.RelationshipModel("owner", "widget", false, true)))),
                List.of("widget.missing: no JSON type for Java type Object, emitted as unknown"));

        String first = ResourceModelJson.write(model);

        assertThat(ResourceModelJson.write(model)).isEqualTo(first);
        assertThat(first).startsWith("{\n  \"formats\": ");
        assertThat(first).endsWith("}\n");
        assertThat(first).doesNotContain("\r");
        assertThat(first).isEqualTo(EXPECTED_JSON);
    }

    private static ResourceModel export(DatabaseFacts db) {
        return new ResourceModelExporter(dictionary(), db, dateFormats()).export();
    }

    private static EntityDictionary dictionary() {
        EntityDictionary dictionary = EntityDictionary.builder().build();
        dictionary.bindEntity(Widget.class);
        dictionary.bindEntity(WidgetDetail.class);
        dictionary.bindEntity(BrokenCtor.class);
        return dictionary;
    }

    private static DateFormats dateFormats() {
        return new DateFormats() {
            @Override
            public Optional<String> formatId(Class<?> javaType) {
                return javaType == Date.class ? Optional.of(RFC3339_SECONDS_UTC) : Optional.empty();
            }

            @Override
            public SortedMap<String, DateFormat> definitions() {
                TreeMap<String, DateFormat> definitions = new TreeMap<>();
                definitions.put(RFC3339_SECONDS_UTC,
                        new DateFormat("rfc3339", "second", "utc-z", "2026-11-19T15:28:21Z", "Second precision UTC."));
                return definitions;
            }
        };
    }

    private static SortedMap<String, DateFormat> formats(String id) {
        TreeMap<String, DateFormat> formats = new TreeMap<>();
        formats.put(id, new DateFormat("rfc3339", "second", "utc-z", "2026-11-19T15:28:21Z", "Second precision UTC."));
        return formats;
    }

    private static ResourceModel.TypeModel type(ResourceModel model, String name) {
        return model.types().stream()
                .filter(type -> type.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static ResourceModel.AttributeModel attribute(ResourceModel.TypeModel type, String name) {
        return type.attributes().stream()
                .filter(attribute -> attribute.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static ResourceModel.RelationshipModel relationship(ResourceModel.TypeModel type, String name) {
        return type.relationships().stream()
                .filter(relationship -> relationship.name().equals(name))
                .findFirst()
                .orElseThrow();
    }
}

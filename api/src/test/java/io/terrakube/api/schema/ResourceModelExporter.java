package io.terrakube.api.schema;

import com.yahoo.elide.annotation.ComputedAttribute;
import com.yahoo.elide.annotation.ReadPermission;
import com.yahoo.elide.core.dictionary.EntityDictionary;
import com.yahoo.elide.core.type.Type;
import jakarta.persistence.Column;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Transient;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Combines Elide's bound entities, the column facts the database reports and the date formats Elide's serdes produce
 * into the final resource model. Deliberately free of Hibernate and JDBC: those arrive through the two interfaces.
 */
public class ResourceModelExporter {

    private final EntityDictionary dictionary;
    private final DatabaseFacts db;
    private final DateFormats dates;
    private final List<String> problems = new ArrayList<>();
    private final Map<String, List<String>> enumsSeen = new HashMap<>();
    private final Set<String> formatIdsUsed = new LinkedHashSet<>();

    public ResourceModelExporter(EntityDictionary dictionary, DatabaseFacts db, DateFormats dates) {
        this.dictionary = dictionary;
        this.db = db;
        this.dates = dates;
    }

    public ResourceModel export() {
        // per-call state: a second export() must not inherit the first call's problems or seen enums
        problems.clear();
        enumsSeen.clear();
        formatIdsUsed.clear();
        List<ResourceModel.TypeModel> types = dictionary.getBoundClasses().stream()
                .sorted(Comparator.comparing(dictionary::getJsonAliasFor))
                .map(this::exportType)
                .toList();
        TreeMap<String, DateFormat> formats = new TreeMap<>(dates.definitions());
        formatIdsUsed.stream()
                .filter(id -> !formats.containsKey(id))
                .forEach(id -> problems.add(id + ": used by a datetime but has no format definition"));
        // the JSON declares every id it references, so anything unused stays out
        formats.keySet().retainAll(formatIdsUsed);
        return new ResourceModel(formats, List.copyOf(types), List.copyOf(problems));
    }

    private ResourceModel.TypeModel exportType(Type<?> type) {
        Class<?> cls = type.getUnderlyingClass().orElseThrow();
        String name = dictionary.getJsonAliasFor(type);
        Object fresh = instantiate(cls, name);

        List<ResourceModel.AttributeModel> attributes = new ArrayList<>();
        for (String attr : sorted(dictionary.getAttributes(type))) {
            attributes.add(exportAttribute(type, cls, name, attr, fresh));
        }

        List<ResourceModel.RelationshipModel> relationships = new ArrayList<>();
        for (String rel : sorted(dictionary.getRelationships(type))) {
            relationships.add(exportRelationship(type, cls, name, rel));
        }

        return new ResourceModel.TypeModel(name, dictionary.isRoot(type), attributes, relationships);
    }

    private ResourceModel.AttributeModel exportAttribute(
            Type<?> type, Class<?> cls, String name, String attr, Object fresh) {
        AnnotatedElement member = member(cls, attr);
        java.lang.reflect.Type javaType = member instanceof Field f ? f.getGenericType()
                : member instanceof Method m ? m.getGenericReturnType()
                : dictionary.getType(type, attr).getUnderlyingClass().orElseThrow();
        TypeDescriptor descriptor = descriptor(javaType, name + "." + attr);

        boolean nullable = true;
        boolean optional = true;
        if (member == null) {
            problems.add(name + "." + attr + ": not a field on " + cls.getSimpleName()
                    + " or its superclasses (computed?)");
        } else {
            boolean computed = member instanceof Method;
            boolean readRestricted = member.isAnnotationPresent(ReadPermission.class);
            optional = readRestricted;
            boolean primitive = javaType instanceof Class<?> c && c.isPrimitive();
            boolean initialized = !computed && fresh != null && read((Field) member, fresh) != null;
            nullable = !(primitive || initialized || (!computed && notNull(member)));
            if (nullable && !computed && !readRestricted && !member.isAnnotationPresent(Transient.class)) {
                nullable = !db.columnNotNull(cls, attr);
            }
        }
        return new ResourceModel.AttributeModel(attr, descriptor, nullable, optional);
    }

    private ResourceModel.RelationshipModel exportRelationship(Type<?> type, Class<?> cls, String name, String rel) {
        AnnotatedElement member = member(cls, rel);
        boolean toOne = member != null && (member.isAnnotationPresent(ManyToOne.class)
                || member.isAnnotationPresent(OneToOne.class));
        boolean annotated = toOne || member != null && (member.isAnnotationPresent(OneToMany.class)
                || member.isAnnotationPresent(ManyToMany.class));
        if (!annotated) {
            problems.add(name + "." + rel + ": no JPA relationship annotation found; emitted as to-many");
        }
        boolean required = false;
        // Hibernate reports the inverse side of a mappedBy one-to-one as the owner's id column, which it doesn't own.
        boolean inverse = member != null && member.isAnnotationPresent(OneToOne.class)
                && !member.getAnnotation(OneToOne.class).mappedBy().isEmpty();
        if (toOne && !inverse && member instanceof Field && !member.isAnnotationPresent(ReadPermission.class)) {
            required = notNull(member) || db.columnNotNull(cls, rel);
        }
        String target = dictionary.getJsonAliasFor(dictionary.getParameterizedType(type, rel));
        return new ResourceModel.RelationshipModel(rel, target, !toOne, required);
    }

    private TypeDescriptor descriptor(java.lang.reflect.Type javaType, String where) {
        Class<?> raw = rawClass(javaType);
        if (raw.isEnum()) {
            return enumDescriptor(raw, where);
        }
        if (raw == String.class || raw == char.class || raw == Character.class || raw == UUID.class) {
            return new TypeDescriptor.Str();
        }
        if (Date.class.isAssignableFrom(raw) || Temporal.class.isAssignableFrom(raw)) {
            Optional<String> id = dates.formatId(raw);
            if (id.isPresent()) {
                formatIdsUsed.add(id.get());
                return new TypeDescriptor.DateTime(id.get());
            }
            problems.add(where + ": no known wire format for Java type " + simpleName(javaType));
            return new TypeDescriptor.Str();
        }
        if (raw == boolean.class || raw == Boolean.class) {
            return new TypeDescriptor.Bool();
        }
        if (raw.isPrimitive() || Number.class.isAssignableFrom(raw)) {
            return new TypeDescriptor.Num();
        }
        if (java.util.Collection.class.isAssignableFrom(raw)) {
            java.lang.reflect.Type item = javaType instanceof ParameterizedType p
                    ? p.getActualTypeArguments()[0]
                    : Object.class;
            return new TypeDescriptor.ArrayOf(descriptor(item, where));
        }
        if (Map.class.isAssignableFrom(raw)) {
            return new TypeDescriptor.MapOf(new TypeDescriptor.Str());
        }
        problems.add(where + ": no JSON type for Java type " + simpleName(javaType) + ", emitted as unknown");
        return new TypeDescriptor.Unknown();
    }

    private TypeDescriptor enumDescriptor(Class<?> raw, String where) {
        List<String> values = Arrays.stream(raw.getEnumConstants()).map(value -> ((Enum<?>) value).name()).toList();
        String name = null;
        // Only the API's own top-level enums get a named const; anything else stays an inline union.
        if (raw.getEnclosingClass() == null && raw.getPackageName().startsWith("io.terrakube.api")) {
            name = raw.getSimpleName();
            List<String> first = enumsSeen.putIfAbsent(name, values);
            if (first != null && !first.equals(values)) {
                problems.add(where + ": Java enum " + name + " already emitted with different values");
            }
        }
        return new TypeDescriptor.EnumOf(name, values);
    }

    private static List<String> sorted(List<String> names) {
        return names.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    /** The backing field, else Elide's @ComputedAttribute getter, nearest class first. */
    private static AnnotatedElement member(Class<?> cls, String name) {
        String getter = "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(getter) && m.isAnnotationPresent(ComputedAttribute.class)) {
                    return m;
                }
            }
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    private static boolean notNull(AnnotatedElement member) {
        Column column = member.getAnnotation(Column.class);
        JoinColumn join = member.getAnnotation(JoinColumn.class);
        ManyToOne manyToOne = member.getAnnotation(ManyToOne.class);
        OneToOne oneToOne = member.getAnnotation(OneToOne.class);
        return member.isAnnotationPresent(NotNull.class) || member.isAnnotationPresent(NotBlank.class)
                || column != null && !column.nullable() || join != null && !join.nullable()
                || manyToOne != null && !manyToOne.optional() || oneToOne != null && !oneToOne.optional();
    }

    private static Class<?> rawClass(java.lang.reflect.Type t) {
        return t instanceof ParameterizedType p ? (Class<?>) p.getRawType()
                : t instanceof Class<?> c ? c
                : Object.class;
    }

    private static String simpleName(java.lang.reflect.Type t) {
        if (t instanceof ParameterizedType p) {
            return rawClass(p).getSimpleName() + Arrays.stream(p.getActualTypeArguments())
                    .map(ResourceModelExporter::simpleName)
                    .collect(Collectors.joining(",", "<", ">"));
        }
        return t instanceof Class<?> c ? c.getSimpleName() : t.getTypeName();
    }

    private Object instantiate(Class<?> cls, String name) {
        try {
            Constructor<?> ctor = cls.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (ReflectiveOperationException | RuntimeException e) {
            // a swallowed failure would silently mark every initialised field nullable
            Throwable failure = e instanceof InvocationTargetException invocation && invocation.getCause() != null
                    ? invocation.getCause()
                    : e;
            problems.add(name + ": no-arg construction failed (" + failure + ")");
            return null;
        }
    }

    private static Object read(Field field, Object target) {
        try {
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}

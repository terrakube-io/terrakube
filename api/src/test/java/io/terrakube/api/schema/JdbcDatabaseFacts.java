package io.terrakube.api.schema;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Transient;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.metamodel.mapping.AttributeMapping;
import org.hibernate.metamodel.mapping.PluralAttributeMapping;
import org.hibernate.metamodel.mapping.SelectableMapping;
import org.hibernate.persister.entity.AbstractEntityPersister;

/**
 * Answers NOT NULL from the live database's catalog after Liquibase has run, so the model follows the schema the API
 * is deployed on rather than what the entity annotations claim.
 */
public class JdbcDatabaseFacts implements DatabaseFacts {

    private final SessionFactoryImplementor sessionFactory;
    private final Map<String, Set<String>> notNullColumnsByTable;

    public JdbcDatabaseFacts(DataSource dataSource, EntityManagerFactory entityManagerFactory) {
        this.sessionFactory = entityManagerFactory.unwrap(SessionFactoryImplementor.class);
        this.notNullColumnsByTable = readNotNullColumns(dataSource);
    }

    @Override
    public boolean columnNotNull(Class<?> entity, String property) {
        Field field = field(entity, property);
        if (field == null || field.isAnnotationPresent(Transient.class) || inverse(field)) {
            return false;
        }
        AbstractEntityPersister persister = persister(entity);
        if (persister == null) {
            return false;
        }
        AttributeMapping attribute;
        try {
            attribute = persister.findAttributeMapping(property);
        } catch (RuntimeException e) {
            return false;
        }
        // a collection's columns live in another table, so they say nothing about this property
        if (attribute == null || attribute instanceof PluralAttributeMapping) {
            return false;
        }
        List<SelectableMapping> columns = new ArrayList<>();
        attribute.forEachSelectable((index, column) -> columns.add(column));
        return !columns.isEmpty() && columns.stream().allMatch(column -> !column.isFormula()
                && notNullColumnsByTable.getOrDefault(normalize(column.getContainingTableExpression()), Set.of())
                        .contains(normalize(column.getSelectionExpression())));
    }

    private static Map<String, Set<String>> readNotNullColumns(DataSource dataSource) {
        Map<String, Set<String>> byTable = new HashMap<>();
        try (Connection connection = dataSource.getConnection();
             ResultSet columns = connection.getMetaData().getColumns(null, connection.getSchema(), "%", "%")) {
            while (columns.next()) {
                if ("NO".equals(columns.getString("IS_NULLABLE"))) {
                    byTable.computeIfAbsent(normalize(columns.getString("TABLE_NAME")), table -> new HashSet<>())
                            .add(normalize(columns.getString("COLUMN_NAME")));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot read column metadata", e);
        }
        return byTable;
    }

    /** Drops any schema qualifier and identifier quoting; catalog and Hibernate names differ only in case. */
    private static String normalize(String name) {
        String unqualified = name.substring(name.lastIndexOf('.') + 1);
        return unqualified.replace("\"", "").replace("`", "").toLowerCase(Locale.ROOT);
    }

    private AbstractEntityPersister persister(Class<?> entity) {
        try {
            return sessionFactory.getMappingMetamodel().getEntityDescriptor(entity) instanceof AbstractEntityPersister p
                    ? p
                    : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean inverse(Field field) {
        OneToOne oneToOne = field.getAnnotation(OneToOne.class);
        OneToMany oneToMany = field.getAnnotation(OneToMany.class);
        ManyToMany manyToMany = field.getAnnotation(ManyToMany.class);
        return oneToOne != null && !oneToOne.mappedBy().isEmpty()
                || oneToMany != null && !oneToMany.mappedBy().isEmpty()
                || manyToMany != null && !manyToMany.mappedBy().isEmpty();
    }

    private static Field field(Class<?> entity, String property) {
        for (Class<?> c = entity; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(property);
            } catch (NoSuchFieldException ignored) {
                // declared further up
            }
        }
        return null;
    }
}

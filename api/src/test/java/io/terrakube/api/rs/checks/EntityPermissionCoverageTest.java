package io.terrakube.api.rs.checks;

import com.yahoo.elide.annotation.CreatePermission;
import com.yahoo.elide.annotation.DeletePermission;
import com.yahoo.elide.annotation.Include;
import com.yahoo.elide.annotation.ReadPermission;
import com.yahoo.elide.annotation.UpdatePermission;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Elide allows any operation that has no permission annotation, and a field without its own
 * annotation falls back to the class one. Requiring all four class-level annotations on every
 * exposed model therefore makes nothing default-allow.
 */
class EntityPermissionCoverageTest {

    private static final List<Class<? extends Annotation>> PERMISSIONS = List.of(
            ReadPermission.class, CreatePermission.class, UpdatePermission.class, DeletePermission.class);

    @Test
    void everyElideModelDeclaresAllClassLevelPermissions() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Include.class));
        Set<BeanDefinition> models = scanner.findCandidateComponents("io.terrakube.api");

        assertThat(models).isNotEmpty();
        for (BeanDefinition model : models) {
            Class<?> type = Class.forName(model.getBeanClassName());
            for (Class<? extends Annotation> permission : PERMISSIONS) {
                assertThat(type.isAnnotationPresent(permission))
                        .as("%s is missing @%s", type.getName(), permission.getSimpleName())
                        .isTrue();
            }
        }
    }
}

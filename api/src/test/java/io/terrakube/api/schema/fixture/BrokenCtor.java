package io.terrakube.api.schema.fixture;

import com.yahoo.elide.annotation.CreatePermission;
import com.yahoo.elide.annotation.DeletePermission;
import com.yahoo.elide.annotation.Include;
import com.yahoo.elide.annotation.ReadPermission;
import com.yahoo.elide.annotation.UpdatePermission;
import jakarta.persistence.Id;

@Include(name = "brokenCtor")
@ReadPermission(expression = "deny")
@CreatePermission(expression = "deny")
@UpdatePermission(expression = "deny")
@DeletePermission(expression = "deny")
public class BrokenCtor {

    @Id
    private Long id;

    private String initialised = "set";

    public BrokenCtor() {
        throw new IllegalStateException("boom");
    }
}

package io.terrakube.api.schema.fixture;

import com.yahoo.elide.annotation.CreatePermission;
import com.yahoo.elide.annotation.DeletePermission;
import com.yahoo.elide.annotation.Include;
import com.yahoo.elide.annotation.ReadPermission;
import com.yahoo.elide.annotation.UpdatePermission;
import jakarta.persistence.Id;
import jakarta.persistence.OneToOne;

@Include(name = "widgetDetail", rootLevel = false)
@ReadPermission(expression = "deny")
@CreatePermission(expression = "deny")
@UpdatePermission(expression = "deny")
@DeletePermission(expression = "deny")
public class WidgetDetail {

    @Id
    private Long id;

    @OneToOne(mappedBy = "detail")
    private Widget widget;
}

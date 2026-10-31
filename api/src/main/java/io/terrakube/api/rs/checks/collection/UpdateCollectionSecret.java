package io.terrakube.api.rs.checks.collection;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import io.terrakube.api.rs.collection.item.Item;

import java.util.Optional;

@SecurityCheck(UpdateCollectionSecret.RULE)
public class UpdateCollectionSecret extends OperationCheck<Item> {

    public static final String RULE = "user update collection secret";

    @Override
    public boolean ok(Item item, RequestScope requestScope, Optional<ChangeSpec> optional) {
        if (optional.isPresent() && Boolean.TRUE.equals(optional.get().getOriginal()) && Boolean.FALSE.equals(optional.get().getModified())) {
            return false;
        }
        return true;
    }
}


package io.terrakube.api.plugin.security.groups;

import com.yahoo.elide.core.security.User;
import io.terrakube.api.rs.Organization;

public interface GroupService {

    boolean isMember(User user, String group);

    boolean isServiceMember(User user, String group);

    boolean isFederatedMember(User user, String group);

    boolean isMemberWithLimitedAccessV1(User user, Object elideEntity);

     boolean isMemberWithLimitedAccessV2(User user, Organization organization);

}

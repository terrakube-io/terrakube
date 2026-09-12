package io.terrakube.api.plugin.security.groups;

import com.yahoo.elide.core.security.User;
import io.terrakube.api.rs.Organization;

import java.util.Set;

public interface GroupService {

    /**
     * Returns the group names that can participate in datastore-level authorization filters.
     *
     * <p>For regular users and PATs these are the token groups. For workload federation this also
     * contains the Terrakube team mapped by the authorized federated credential.
     */
    Set<String> getEffectiveGroups(User user);

    boolean isMember(User user, String group);

    boolean isServiceMember(User user, String group);

    boolean isFederatedMember(User user, String group);

    boolean isMemberWithLimitedAccessV1(User user, Object elideEntity);

     boolean isMemberWithLimitedAccessV2(User user, Organization organization);

     boolean isMemberWithProjectAccess(User user, Organization organization);

}

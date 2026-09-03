package io.terrakube.api.plugin.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yahoo.elide.core.security.User;
import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.project.access.ProjectAccess;
import io.terrakube.api.rs.team.Team;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.access.Access;
import io.terrakube.api.rs.workspace.tag.WorkspaceTag;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@AllArgsConstructor
public class WorkspacePageService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final EntityManager entityManager;
    private final AuthenticatedUser authenticatedUser;
    private final GroupService groupService;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public WorkspacePage getPage(
            String organizationId,
            Integer requestedFirst,
            String after,
            String search,
            String status,
            List<String> tagIds,
            String projectId,
            WorkspaceSort requestedSort,
            User user) {
        UUID organizationUuid = UUID.fromString(organizationId);
        int first = Math.min(Math.max(requestedFirst == null ? DEFAULT_PAGE_SIZE : requestedFirst, 1), MAX_PAGE_SIZE);
        WorkspaceSort sort = requestedSort == null ? WorkspaceSort.NAME_ASC : requestedSort;
        Set<String> groups = groupService.getEffectiveGroups(user);
        boolean superUser = authenticatedUser.isSuperUser(user);
        WorkspaceCursor cursor = decodeCursor(after, sort);

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Workspace> pageQuery = cb.createQuery(Workspace.class);
        Root<Workspace> workspace = pageQuery.from(Workspace.class);
        List<Predicate> predicates = basePredicates(
                pageQuery, cb, workspace, organizationUuid, search, status, tagIds, projectId, groups, superUser);
        SortExpression sortExpression = sortExpression(cb, workspace, sort);
        if (cursor != null) {
            predicates.add(cursorPredicate(cb, workspace, sortExpression, cursor));
        }
        pageQuery.select(workspace)
                .where(predicates.toArray(Predicate[]::new))
                .orderBy(sortExpression.orders(cb, workspace.get("id")));

        List<Workspace> page = entityManager.createQuery(pageQuery).setMaxResults(first + 1).getResultList();
        boolean hasNextPage = page.size() > first;
        if (hasNextPage) {
            page = new ArrayList<>(page.subList(0, first));
        }
        page = hydrate(page);

        long totalRecords = count(
                organizationUuid, search, status, tagIds, projectId, groups, superUser);
        WorkspaceStatusCounts statusCounts = statusCounts(
                organizationUuid, search, tagIds, projectId, groups, superUser);
        String endCursor = page.isEmpty() ? null : encodeCursor(page.get(page.size() - 1), sort);

        return new WorkspacePage(
                page.stream().map(this::toListItem).toList(),
                new WorkspacePageInfo(endCursor, hasNextPage, Math.toIntExact(totalRecords)),
                statusCounts);
    }

    private long count(
            UUID organizationId,
            String search,
            String status,
            List<String> tagIds,
            String projectId,
            Set<String> groups,
            boolean superUser) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<Workspace> workspace = query.from(Workspace.class);
        List<Predicate> predicates = basePredicates(
                query, cb, workspace, organizationId, search, status, tagIds, projectId, groups, superUser);
        query.select(cb.countDistinct(workspace)).where(predicates.toArray(Predicate[]::new));
        return entityManager.createQuery(query).getSingleResult();
    }

    private WorkspaceStatusCounts statusCounts(
            UUID organizationId,
            String search,
            List<String> tagIds,
            String projectId,
            Set<String> groups,
            boolean superUser) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<Workspace> workspace = query.from(Workspace.class);
        List<Predicate> predicates = basePredicates(
                query, cb, workspace, organizationId, search, null, tagIds, projectId, groups, superUser);
        Path<JobStatus> statusPath = workspace.get("lastJobStatus");
        query.multiselect(
                        cb.count(workspace),
                        statusCount(cb, statusPath, JobStatus.waitingApproval),
                        statusCount(cb, statusPath, JobStatus.failed),
                        statusCount(cb, statusPath, JobStatus.pending),
                        statusCount(cb, statusPath, JobStatus.queue),
                        statusCount(cb, statusPath, JobStatus.running),
                        statusCount(cb, statusPath, JobStatus.completed),
                        cb.coalesce(
                                cb.sum(cb.<Long>selectCase()
                                        .when(cb.or(
                                                cb.isNull(statusPath),
                                                cb.equal(statusPath, JobStatus.NeverExecuted)), 1L)
                                        .otherwise(0L)),
                                0L))
                .where(predicates.toArray(Predicate[]::new));

        Tuple counts = entityManager.createQuery(query).getSingleResult();
        return new WorkspaceStatusCounts(
                Math.toIntExact(counts.get(0, Long.class)),
                Math.toIntExact(counts.get(1, Long.class)),
                Math.toIntExact(counts.get(2, Long.class)),
                Math.toIntExact(counts.get(3, Long.class)),
                Math.toIntExact(counts.get(4, Long.class)),
                Math.toIntExact(counts.get(5, Long.class)),
                Math.toIntExact(counts.get(6, Long.class)),
                Math.toIntExact(counts.get(7, Long.class)));
    }

    private Expression<Long> statusCount(CriteriaBuilder cb, Path<JobStatus> statusPath, JobStatus status) {
        return cb.coalesce(
                cb.sum(cb.<Long>selectCase()
                        .when(cb.equal(statusPath, status), 1L)
                        .otherwise(0L)),
                0L);
    }

    private List<Predicate> basePredicates(
            CriteriaQuery<?> query,
            CriteriaBuilder cb,
            Root<Workspace> workspace,
            UUID organizationId,
            String search,
            String status,
            List<String> tagIds,
            String projectId,
            Set<String> groups,
            boolean superUser) {
        List<Predicate> predicates = new ArrayList<>();
        predicates.add(cb.equal(workspace.get("organization").get("id"), organizationId));
        predicates.add(cb.isFalse(workspace.get("deleted")));

        if (search != null && !search.isBlank()) {
            String pattern = "%" + escapeLike(search.trim().toLowerCase(Locale.ROOT)) + "%";
            predicates.add(cb.or(
                    cb.like(cb.lower(workspace.get("name")), pattern, '\\'),
                    cb.like(cb.lower(cb.coalesce(workspace.get("description"), "")), pattern, '\\')));
        }
        if (status != null && !status.isBlank() && !"All".equals(status)) {
            if ("NeverExecuted".equals(status)) {
                predicates.add(cb.or(
                        cb.isNull(workspace.get("lastJobStatus")),
                        cb.equal(workspace.get("lastJobStatus"), JobStatus.NeverExecuted)));
            } else {
                predicates.add(cb.equal(workspace.get("lastJobStatus"), JobStatus.valueOf(status)));
            }
        }
        if (projectId != null && !projectId.isBlank()) {
            if ("__unassigned__".equals(projectId)) {
                predicates.add(cb.isNull(workspace.get("project")));
            } else {
                predicates.add(cb.equal(workspace.get("project").get("id"), UUID.fromString(projectId)));
            }
        }
        if (tagIds != null && !tagIds.isEmpty()) {
            Subquery<Integer> tagQuery = query.subquery(Integer.class);
            Root<WorkspaceTag> tag = tagQuery.from(WorkspaceTag.class);
            tagQuery.select(cb.literal(1)).where(
                    cb.equal(tag.get("workspace"), workspace),
                    tag.get("tagId").in(tagIds));
            predicates.add(cb.exists(tagQuery));
        }
        if (!superUser) {
            predicates.add(authorizationPredicate(query, cb, workspace, groups));
        }
        return predicates;
    }

    private Predicate authorizationPredicate(
            CriteriaQuery<?> query,
            CriteriaBuilder cb,
            Root<Workspace> workspace,
            Set<String> groups) {
        if (groups.isEmpty()) {
            return cb.disjunction();
        }

        Subquery<Integer> teamQuery = query.subquery(Integer.class);
        Root<Team> team = teamQuery.from(Team.class);
        Predicate teamCanView = cb.or(
                cb.isNull(workspace.get("project")),
                managerPredicate(cb, team.get("role"), team.get("manageWorkspace")));
        teamQuery.select(cb.literal(1)).where(
                cb.equal(team.get("organization"), workspace.get("organization")),
                team.get("name").in(groups),
                teamCanView);

        Subquery<Integer> projectQuery = query.subquery(Integer.class);
        Root<ProjectAccess> projectAccess = projectQuery.from(ProjectAccess.class);
        projectQuery.select(cb.literal(1)).where(
                cb.equal(projectAccess.get("project"), workspace.get("project")),
                projectAccess.get("name").in(groups));

        Subquery<Integer> workspaceQuery = query.subquery(Integer.class);
        Root<Access> access = workspaceQuery.from(Access.class);
        workspaceQuery.select(cb.literal(1)).where(
                cb.equal(access.get("workspace"), workspace),
                access.get("name").in(groups),
                managerPredicate(cb, access.get("role"), access.get("manageWorkspace")));

        return cb.or(cb.exists(teamQuery), cb.exists(projectQuery), cb.exists(workspaceQuery));
    }

    private Predicate managerPredicate(CriteriaBuilder cb, Path<String> role, Path<Boolean> manageWorkspace) {
        Expression<String> normalizedRole = cb.lower(cb.coalesce(role, "custom"));
        return cb.or(
                normalizedRole.in("admin", "write"),
                cb.and(normalizedRole.in("", "custom"), cb.isTrue(manageWorkspace)));
    }

    private SortExpression sortExpression(CriteriaBuilder cb, Root<Workspace> workspace, WorkspaceSort sort) {
        return switch (sort) {
            case NAME_ASC -> SortExpression.text(cb.lower(cb.coalesce(workspace.get("name"), "")), true);
            case NAME_DESC -> SortExpression.text(cb.lower(cb.coalesce(workspace.get("name"), "")), false);
            case LAST_RUN_ASC -> SortExpression.date(cb.coalesce(workspace.get("lastJobDate"), new Date(0)), true);
            case LAST_RUN_DESC -> SortExpression.date(cb.coalesce(workspace.get("lastJobDate"), new Date(0)), false);
            case STATUS -> SortExpression.number(statusRank(cb, workspace), true);
            case SOURCE_ASC -> SortExpression.text(cb.lower(cb.coalesce(workspace.get("source"), "")), true);
            case SOURCE_DESC -> SortExpression.text(cb.lower(cb.coalesce(workspace.get("source"), "")), false);
            case TERRAFORM_VERSION_ASC -> SortExpression.text(
                    cb.lower(cb.coalesce(workspace.get("terraformVersion"), "")), true);
            case TERRAFORM_VERSION_DESC -> SortExpression.text(
                    cb.lower(cb.coalesce(workspace.get("terraformVersion"), "")), false);
        };
    }

    private Expression<Integer> statusRank(CriteriaBuilder cb, Root<Workspace> workspace) {
        Path<JobStatus> status = workspace.get("lastJobStatus");
        CriteriaBuilder.Case<Integer> rank = cb.selectCase();
        return rank.when(cb.equal(status, JobStatus.running), 0)
                .when(cb.equal(status, JobStatus.queue), 1)
                .when(cb.equal(status, JobStatus.waitingApproval), 2)
                .when(cb.equal(status, JobStatus.failed), 3)
                .when(cb.equal(status, JobStatus.rejected), 4)
                .when(cb.equal(status, JobStatus.cancelled), 5)
                .when(cb.equal(status, JobStatus.completed), 6)
                .when(cb.equal(status, JobStatus.noChanges), 7)
                .when(cb.equal(status, JobStatus.notExecuted), 8)
                .when(cb.equal(status, JobStatus.approved), 9)
                .when(cb.equal(status, JobStatus.pending), 10)
                .when(cb.equal(status, JobStatus.unknown), 11)
                .otherwise(12);
    }

    private Predicate cursorPredicate(
            CriteriaBuilder cb,
            Root<Workspace> workspace,
            SortExpression sort,
            WorkspaceCursor cursor) {
        UUID id = UUID.fromString(cursor.id());
        Predicate valueAfter = sort.after(cb, cursor.value());
        Predicate sameValueAndIdAfter = cb.and(
                sort.equal(cb, cursor.value()),
                sort.ascending()
                        ? cb.greaterThan(workspace.get("id"), id)
                        : cb.lessThan(workspace.get("id"), id));
        return cb.or(valueAfter, sameValueAndIdAfter);
    }

    private List<Workspace> hydrate(List<Workspace> page) {
        if (page.isEmpty()) {
            return page;
        }
        List<UUID> ids = page.stream().map(Workspace::getId).toList();
        List<Workspace> hydrated = entityManager.createQuery(
                        "select distinct w from workspace w "
                                + "left join fetch w.workspaceTag left join fetch w.project where w.id in :ids",
                        Workspace.class)
                .setParameter("ids", ids)
                .getResultList();
        Map<UUID, Workspace> byId = new LinkedHashMap<>();
        hydrated.forEach(item -> byId.put(item.getId(), item));
        return ids.stream().map(byId::get).toList();
    }

    private WorkspaceListItem toListItem(Workspace workspace) {
        return new WorkspaceListItem(
                workspace.getId().toString(),
                workspace.getName(),
                workspace.getDescription(),
                workspace.getSource(),
                workspace.getBranch(),
                workspace.getTerraformVersion(),
                workspace.getIacType(),
                workspace.getLastJobStatus() == null ? null : workspace.getLastJobStatus().name(),
                workspace.getLastJobDate() == null ? null : workspace.getLastJobDate().toInstant().toString(),
                workspace.isLocked(),
                workspace.getWorkspaceTag().stream().map(WorkspaceTag::getTagId).toList(),
                workspace.getProject() == null ? null : workspace.getProject().getId().toString(),
                workspace.getProject() == null ? null : workspace.getProject().getName());
    }

    private String encodeCursor(Workspace workspace, WorkspaceSort sort) {
        WorkspaceCursor cursor = new WorkspaceCursor(sort.name(), sortValue(workspace, sort), workspace.getId().toString());
        try {
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(objectMapper.writeValueAsBytes(cursor));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to encode workspace cursor", exception);
        }
    }

    private WorkspaceCursor decodeCursor(String encoded, WorkspaceSort sort) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            WorkspaceCursor cursor = objectMapper.readValue(
                    Base64.getUrlDecoder().decode(encoded.getBytes(StandardCharsets.UTF_8)), WorkspaceCursor.class);
            if (!sort.name().equals(cursor.sort())) {
                throw new IllegalArgumentException("Workspace cursor does not match the requested sort");
            }
            return cursor;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid workspace cursor", exception);
        }
    }

    private String sortValue(Workspace workspace, WorkspaceSort sort) {
        return switch (sort) {
            case NAME_ASC, NAME_DESC -> lower(workspace.getName());
            case LAST_RUN_ASC, LAST_RUN_DESC -> String.valueOf(
                    workspace.getLastJobDate() == null ? 0 : workspace.getLastJobDate().getTime());
            case STATUS -> String.valueOf(statusRank(workspace.getLastJobStatus()));
            case SOURCE_ASC, SOURCE_DESC -> lower(workspace.getSource());
            case TERRAFORM_VERSION_ASC, TERRAFORM_VERSION_DESC -> lower(workspace.getTerraformVersion());
        };
    }

    private int statusRank(JobStatus status) {
        if (status == null) return 12;
        return switch (status) {
            case running -> 0;
            case queue -> 1;
            case waitingApproval -> 2;
            case failed -> 3;
            case rejected -> 4;
            case cancelled -> 5;
            case completed -> 6;
            case noChanges -> 7;
            case notExecuted -> 8;
            case approved -> 9;
            case pending -> 10;
            case unknown -> 11;
            case NeverExecuted -> 12;
        };
    }

    private String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    public enum WorkspaceSort {
        NAME_ASC,
        NAME_DESC,
        LAST_RUN_ASC,
        LAST_RUN_DESC,
        STATUS,
        SOURCE_ASC,
        SOURCE_DESC,
        TERRAFORM_VERSION_ASC,
        TERRAFORM_VERSION_DESC
    }

    public record WorkspacePage(
            List<WorkspaceListItem> nodes,
            WorkspacePageInfo pageInfo,
            WorkspaceStatusCounts statusCounts) {}

    public record WorkspacePageInfo(String endCursor, boolean hasNextPage, int totalRecords) {}

    public record WorkspaceStatusCounts(
            int all,
            int waitingApproval,
            int failed,
            int pending,
            int queue,
            int running,
            int completed,
            int neverExecuted) {}

    public record WorkspaceListItem(
            String id,
            String name,
            String description,
            String source,
            String branch,
            String terraformVersion,
            String iacType,
            String lastJobStatus,
            String lastJobDate,
            boolean locked,
            List<String> tagIds,
            String projectId,
            String projectName) {}

    private record WorkspaceCursor(String sort, String value, String id) {}

    private record SortExpression(Expression<?> expression, ValueType type, boolean ascending) {
        static SortExpression text(Expression<String> expression, boolean ascending) {
            return new SortExpression(expression, ValueType.TEXT, ascending);
        }

        static SortExpression date(Expression<Date> expression, boolean ascending) {
            return new SortExpression(expression, ValueType.DATE, ascending);
        }

        static SortExpression number(Expression<Integer> expression, boolean ascending) {
            return new SortExpression(expression, ValueType.NUMBER, ascending);
        }

        List<Order> orders(CriteriaBuilder cb, Path<UUID> id) {
            return ascending
                    ? List.of(cb.asc(expression), cb.asc(id))
                    : List.of(cb.desc(expression), cb.desc(id));
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        Predicate after(CriteriaBuilder cb, String value) {
            Comparable parsed = parsed(value);
            Expression<? extends Comparable> comparable = (Expression<? extends Comparable>) expression;
            return ascending ? cb.greaterThan(comparable, parsed) : cb.lessThan(comparable, parsed);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        Predicate equal(CriteriaBuilder cb, String value) {
            return cb.equal(expression, parsed(value));
        }

        private Comparable<?> parsed(String value) {
            return switch (type) {
                case TEXT -> value;
                case DATE -> new Date(Long.parseLong(value));
                case NUMBER -> Integer.valueOf(value);
            };
        }
    }

    private enum ValueType {
        TEXT,
        DATE,
        NUMBER
    }
}

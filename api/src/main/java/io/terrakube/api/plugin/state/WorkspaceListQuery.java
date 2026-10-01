package io.terrakube.api.plugin.state;

import org.springframework.util.MultiValueMap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Filters accepted by the TFE "list workspaces" endpoint. Every filter present is applied (AND).
 *
 * <p>The Terraform CLI sends key/value tags as {@code filter[tagged][N][key]} / {@code filter[tagged][N][value]}
 * (the value is always sent, empty for key-only tags) and repeats the keys in {@code search[tags]}. N comes from
 * iterating a Go map, so its order carries no meaning.
 */
record WorkspaceListQuery(List<String> searchTags, List<TagFilter> tagged, String searchName, String projectId,
                          int pageNumber, int pageSize) {

    // Same defaults as the HCP Terraform API: 20 per page, at most 100
    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;

    private static final Pattern TAGGED_PARAMETER = Pattern.compile("^filter\\[tagged]\\[(\\d+)]\\[(key|value)]$");

    record TagFilter(String key, String value) {
        boolean hasValue() {
            return value != null && !value.isEmpty();
        }
    }

    static WorkspaceListQuery from(MultiValueMap<String, String> parameters) {
        List<String> searchTags = new ArrayList<>();
        String tags = parameters.getFirst("search[tags]");
        if (tags != null) {
            Arrays.stream(tags.split(",")).map(String::trim).filter(tag -> !tag.isEmpty()).forEach(searchTags::add);
        }

        // Keyed by the raw index text: only pairing key and value matters, not the order.
        Map<String, String[]> taggedByIndex = new TreeMap<>();
        for (Map.Entry<String, List<String>> parameter : parameters.entrySet()) {
            Matcher matcher = TAGGED_PARAMETER.matcher(parameter.getKey());
            if (!matcher.matches() || parameter.getValue() == null || parameter.getValue().isEmpty()) {
                continue;
            }
            String[] keyValue = taggedByIndex.computeIfAbsent(matcher.group(1), index -> new String[2]);
            keyValue["key".equals(matcher.group(2)) ? 0 : 1] = parameter.getValue().get(0);
        }
        List<TagFilter> tagged = new ArrayList<>();
        taggedByIndex.values().stream()
                .filter(keyValue -> keyValue[0] != null && !keyValue[0].isEmpty())
                .forEach(keyValue -> tagged.add(new TagFilter(keyValue[0], keyValue[1] == null ? "" : keyValue[1])));

        return new WorkspaceListQuery(searchTags, tagged, emptyToNull(parameters.getFirst("search[name]")),
                emptyToNull(parameters.getFirst("filter[project][id]")), parsePageNumber(parameters.getFirst("page[number]")),
                parsePageSize(parameters.getFirst("page[size]")));
    }

    boolean hasAnyCondition() {
        return !searchTags.isEmpty() || !tagged.isEmpty() || searchName != null || projectId != null;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static int parsePageNumber(String pageNumber) {
        try {
            return pageNumber == null ? 1 : Math.max(1, Integer.parseInt(pageNumber.trim()));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static int parsePageSize(String pageSize) {
        try {
            int size = pageSize == null ? DEFAULT_PAGE_SIZE : Integer.parseInt(pageSize.trim());
            return size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        } catch (NumberFormatException e) {
            return DEFAULT_PAGE_SIZE;
        }
    }

    /** Zero-based index of the first match on the requested page. */
    long offset() {
        return (long) (pageNumber - 1) * pageSize;
    }
}

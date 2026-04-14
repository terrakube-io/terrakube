package io.terrakube.api.plugin.importer.tfcloud;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class VarsetListResponse {
    private List<VarsetData> data;
    private VarsetMeta meta;

    @Getter
    @Setter
    public static class VarsetData {
        private String id;
        private String type;
        private VarsetAttributes attributes;
    }

    @Getter
    @Setter
    public static class VarsetAttributes {
        private String name;
    }

    @Getter
    @Setter
    public static class VarsetMeta {
        private Pagination pagination;

        @Getter
        @Setter
        public static class Pagination {
            @JsonProperty("current-page")
            private int currentPage;

            @JsonProperty("prev-page")
            private Integer prevPage;

            @JsonProperty("next-page")
            private Integer nextPage;

            @JsonProperty("total-pages")
            private int totalPages;

            @JsonProperty("total-count")
            private int totalCount;
        }
    }
}

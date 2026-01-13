package io.terrakube.api.plugin.vcs.provider.github;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GitHubToken {
    private String access_token;
    private String token_type;
    private String scope;
    private String refresh_token;
    private Integer expires_in;
}

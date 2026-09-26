package io.terrakube.api.plugin.token.login;

import java.util.List;

public record DexIdentity(String email, String name, List<String> groups) {
}

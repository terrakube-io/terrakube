package io.terrakube.registry.service.search;

import java.util.regex.Pattern;

/**
 * Validates organization, module, provider, platform, and version path parameters against strict
 * allowlists before interpolating them into Elide RSQL or GraphQL queries.
 */
public final class RegistryPathValidator {

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");
    private static final Pattern VERSION_PATTERN = Pattern.compile("^[0-9A-Za-z][0-9A-Za-z.+_-]{0,127}$");

    private RegistryPathValidator() {
    }

    public static boolean isValidIdentifier(String value) {
        return value != null && IDENTIFIER_PATTERN.matcher(value).matches();
    }

    public static boolean isValidVersion(String value) {
        return value != null && VERSION_PATTERN.matcher(value).matches();
    }
}

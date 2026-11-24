package io.terrakube.api.rs.collection;

/**
 * What a collection reference attaches its collection to. WORKSPACE is the original behaviour and the default for
 * existing rows. Narrower scopes win over wider ones when the same key is defined by more than one collection.
 */
public enum ReferenceScope {
    WORKSPACE,
    PROJECT,
    ORGANIZATION
}

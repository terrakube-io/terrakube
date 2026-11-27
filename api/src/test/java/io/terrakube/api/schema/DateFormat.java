package io.terrakube.api.schema;

/** How one wire date format reads, in language-neutral terms a parser can pick a decoder from. */
public record DateFormat(String standard, String precision, String offset, String example, String description) {
}

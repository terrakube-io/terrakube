import quoteRsql from "./quoteRsql";

/**
 * The value of a case-insensitive "contains" filter (`name=ini=...`): quoted, so the spaces and punctuation a person
 * types stay part of the text instead of ending the value. Null when there is nothing to search for.
 */
export default function containsFilter(text: string): string | null {
  const term = text.replace(/\*/g, " ").trim();
  return term ? quoteRsql(`*${term}*`) : null;
}

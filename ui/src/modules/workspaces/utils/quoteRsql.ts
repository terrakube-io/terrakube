/** A value as an RSQL string literal, so spaces, commas, quotes and parentheses in it stay part of the value. */
export default function quoteRsql(value: string): string {
  return `"${value.replace(/\\/g, "\\\\").replace(/"/g, '\\"')}"`;
}

// The API records why it closed a run (it was stuck, or an approval timed out) as a line in the run's output.
const CLOSED_BY_TERRAKUBE_PREFIX = "Closed by Terrakube: ";

/** The reason Terrakube closed the run, if it did. */
export function parseClosedByTerrakube(jobOutput?: string | null): string | null {
  if (!jobOutput) return null;
  const line = jobOutput
    .split("\n")
    .map((entry) => entry.trim())
    .find((entry) => entry.startsWith(CLOSED_BY_TERRAKUBE_PREFIX));
  if (!line) return null;
  const reason = line.slice(CLOSED_BY_TERRAKUBE_PREFIX.length).trim();
  return reason === "" ? null : reason;
}

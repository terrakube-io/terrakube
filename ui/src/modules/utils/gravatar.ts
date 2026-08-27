// Gravatar's current API accepts a SHA256 hash of the (trimmed, lowercased) email
// directly, computed here with the native Web Crypto API — no extra dependency needed.
export async function getGravatarUrl(email: string): Promise<string> {
  const normalized = email.trim().toLowerCase();
  const data = new TextEncoder().encode(normalized);
  const hashBuffer = await crypto.subtle.digest("SHA-256", data);
  const hash = Array.from(new Uint8Array(hashBuffer))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
  // d=404 makes Gravatar return a 404 instead of a default placeholder image when
  // the email has no registered Gravatar, so <Avatar> falls back to its own icon.
  return `https://www.gravatar.com/avatar/${hash}?d=404`;
}

export default getGravatarUrl;

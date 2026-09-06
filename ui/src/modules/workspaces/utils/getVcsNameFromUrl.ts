import formatSshUrl from "./formatSshUrl";

export default function (normalizedUrl: string): string {
  // Let's just be safe in case the wrong url was passed
  const fixedUrl = formatSshUrl(normalizedUrl);
  try {
    return new URL(fixedUrl).pathname.substring(1);
  } catch {
    return normalizedUrl;
  }
}

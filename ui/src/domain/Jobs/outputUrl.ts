export function getPublicApiOrigin(): string {
  return new URL(window._env_.REACT_APP_TERRAKUBE_API_URL).origin;
}

export function getJobOutputRequestUrl(output: string): string {
  const publicApiOrigin = getPublicApiOrigin();

  try {
    const outputUrl = new URL(output);

    if (outputUrl.origin === publicApiOrigin) {
      return outputUrl.toString();
    }

    if (outputUrl.pathname.startsWith("/tfoutput/")) {
      return `${publicApiOrigin}${outputUrl.pathname}${outputUrl.search}${outputUrl.hash}`;
    }

    return output;
  } catch {
    return new URL(output, publicApiOrigin).toString();
  }
}

export function isTerrakubeApiUrl(url: string): boolean {
  const publicApiOrigin = getPublicApiOrigin();

  try {
    const resolvedUrl = new URL(url, publicApiOrigin);
    return resolvedUrl.origin === publicApiOrigin;
  } catch {
    return false;
  }
}

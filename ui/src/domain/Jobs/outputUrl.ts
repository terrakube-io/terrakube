import { apiPrefix, getPublicApiOrigin } from "@/config/apiUrls";

export { getPublicApiOrigin };

export function getJobOutputRequestUrl(output: string): string {
  const publicApiOrigin = getPublicApiOrigin();

  try {
    const outputUrl = new URL(output);

    if (outputUrl.origin === publicApiOrigin) {
      return outputUrl.toString();
    }

    if (outputUrl.pathname.startsWith("/tfoutput/")) {
      return `${publicApiOrigin}${apiPrefix()}${outputUrl.pathname}${outputUrl.search}${outputUrl.hash}`;
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

// UTF-8 safe base64 for action and template sources stored by the API. `btoa`/`atob` alone only
// handle Latin-1; going through bytes keeps any Unicode text intact.
export const encodeBase64 = (text: string): string =>
  btoa(Array.from(new TextEncoder().encode(text), (byte) => String.fromCharCode(byte)).join(""));

export const decodeBase64 = (base64: string): string =>
  new TextDecoder().decode(Uint8Array.from(atob(base64), (char) => char.charCodeAt(0)));

export const OPEN_PALETTE_EVENT = "terrakube:open-palette";

/** Opens the command palette from anywhere, for example a button in the sidebar. */
export function openCommandPalette() {
  window.dispatchEvent(new Event(OPEN_PALETTE_EVENT));
}

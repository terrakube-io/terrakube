const EVENT = "terrakube:project-changed";

/** Tells anything that shows a project's numbers, such as the sidebar, that workspaces moved in or out. */
export function notifyProjectChanged(): void {
  window.dispatchEvent(new Event(EVENT));
}

/** Runs the callback whenever a project changed. Returns the function that stops listening. */
export function onProjectChanged(callback: () => void): () => void {
  window.addEventListener(EVENT, callback);
  return () => window.removeEventListener(EVENT, callback);
}

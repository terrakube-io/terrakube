import { RefObject, useEffect } from "react";

/** Pressing "/" outside a text field moves focus to the given input, as on most developer tools. */
export function useSlashToFocus(ref: RefObject<{ focus: () => void } | null>) {
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== "/" || event.metaKey || event.ctrlKey || event.altKey) return;
      const target = event.target as HTMLElement | null;
      const typing = target && (["INPUT", "TEXTAREA", "SELECT"].includes(target.tagName) || target.isContentEditable);
      if (typing) return;
      event.preventDefault();
      ref.current?.focus();
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [ref]);
}

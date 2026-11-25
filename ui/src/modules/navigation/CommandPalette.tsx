import { Input, Modal, Typography } from "antd";
import type { InputRef } from "antd";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { getOrgIdFromPathname } from "@/config/orgId";
import { searchOrganization } from "./navigationService";
import { OPEN_PALETTE_EVENT } from "./openPalette";
import { PaletteItem, loadRecents, matchesQuery, pageItems, rank, rememberRecent } from "./paletteModel";
import "./CommandPalette.css";

// Keys contain colons and ids from the server, neither of which is safe in a CSS selector, so ids are cleaned.
const optionId = (key: string) => `palette-${key.replace(/[^a-zA-Z0-9_-]/g, "-")}`;

type Props = {
  organizations: { id: string; name: string }[];
};

const SEARCH_DEBOUNCE_MS = 200;
// Searching every organization costs one request each, so a long list is searched only in the current one.
const MAX_ORGANIZATIONS_SEARCHED = 12;
const KIND_LABEL: Record<PaletteItem["kind"], string> = {
  page: "Page",
  organization: "Organization",
  project: "Project",
  workspace: "Workspace",
};

/** Cmd+K or Ctrl+K: jump to a page, organization, project or workspace by typing part of its name. */
export default function CommandPalette({ organizations }: Props) {
  const navigate = useNavigate();
  const location = useLocation();
  const currentOrgId = getOrgIdFromPathname(location.pathname) ?? null;
  const currentOrg = organizations.find((org) => org.id === currentOrgId);
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [remote, setRemote] = useState<PaletteItem[]>([]);
  const [searching, setSearching] = useState(false);
  const [failed, setFailed] = useState(false);
  const [active, setActive] = useState(0);
  const inputRef = useRef<InputRef>(null);
  const sequence = useRef(0);
  const openRef = useRef(false);
  useEffect(() => {
    openRef.current = open;
  }, [open]);

  const close = useCallback(() => {
    // A search still on its way must not fill the list of the next time the palette opens.
    sequence.current += 1;
    setOpen(false);
    setQuery("");
    setRemote([]);
    setActive(0);
  }, []);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key.toLowerCase() === "k" && (event.metaKey || event.ctrlKey) && !event.altKey) {
        // Typing inside another dialog must not open the palette on top of it. Focus is trapped in an open dialog, so
        // where the key press comes from says whether one is open, which a modal kept in the page after closing never does.
        const insideOtherDialog = !openRef.current && (event.target as Element | null)?.closest?.('[role="dialog"]');
        if (insideOtherDialog) return;
        event.preventDefault();
        if (openRef.current) close();
        else setOpen(true);
      }
    };
    const onOpen = () => setOpen(true);
    window.addEventListener("keydown", onKeyDown);
    window.addEventListener(OPEN_PALETTE_EVENT, onOpen);
    return () => {
      window.removeEventListener("keydown", onKeyDown);
      window.removeEventListener(OPEN_PALETTE_EVENT, onOpen);
    };
  }, [close]);

  useEffect(() => {
    const text = query.trim();
    if (!open || !text) {
      setRemote([]);
      setSearching(false);
      setFailed(false);
      return;
    }
    const mine = ++sequence.current;
    // The results of the text before this one are not an answer to it: Enter must not open one of them.
    setRemote([]);
    setSearching(true);
    const handle = window.setTimeout(async () => {
      const scope = organizations.length <= MAX_ORGANIZATIONS_SEARCHED ? organizations : currentOrg ? [currentOrg] : [];
      const settled = await Promise.allSettled(scope.map((org) => searchOrganization(org.id, text)));
      if (mine !== sequence.current) return;
      const items: PaletteItem[] = [];
      settled.forEach((result, index) => {
        if (result.status !== "fulfilled") return;
        const org = scope[index];
        result.value.projects.forEach((project) =>
          items.push({
            key: `project:${project.id}`,
            kind: "project",
            title: project.name,
            subtitle: org.name,
            to: `/organizations/${org.id}/projects/${project.id}`,
          })
        );
        result.value.workspaces.forEach((workspace) =>
          items.push({
            key: `workspace:${workspace.id}`,
            kind: "workspace",
            title: workspace.name,
            subtitle: org.name,
            to: `/organizations/${org.id}/workspaces/${workspace.id}`,
          })
        );
      });
      setFailed(settled.length > 0 && settled.every((result) => result.status === "rejected"));
      setRemote(items);
      setSearching(false);
    }, SEARCH_DEBOUNCE_MS);
    return () => window.clearTimeout(handle);
  }, [query, open, organizations, currentOrg]);

  const items = useMemo<PaletteItem[]>(() => {
    const local: PaletteItem[] = [
      ...pageItems(currentOrgId, currentOrg?.name),
      ...organizations.map((org) => ({
        key: `org:${org.id}`,
        kind: "organization" as const,
        title: org.name,
        to: `/organizations/${org.id}/projects`,
      })),
    ];
    if (!query.trim())
      return [...loadRecents(), ...local.filter((item) => item.kind === "page" || item.kind === "organization")];
    // A result can arrive twice (a project listed under two searches), but one row per thing is enough.
    const seen = new Set<string>();
    const unique = [...local.filter((item) => matchesQuery(item, query)), ...remote].filter((item) => {
      if (seen.has(item.key)) return false;
      seen.add(item.key);
      return true;
    });
    return rank(unique, query);
    // loadRecents reads storage, so it is re-read each time the palette opens.
  }, [query, remote, organizations, currentOrgId, currentOrg, open]);

  useEffect(() => setActive(0), [query]);

  // The list scrolls, and the highlight moves with the arrow keys, so the highlighted row is kept in view.
  useEffect(() => {
    const item = items[active];
    if (item) document.getElementById(optionId(item.key))?.scrollIntoView?.({ block: "nearest" });
  }, [active, items]);

  const choose = (item: PaletteItem) => {
    if (item.kind === "project" || item.kind === "workspace") rememberRecent(item);
    close();
    navigate(item.to);
  };

  const onKeyDown = (event: React.KeyboardEvent) => {
    if (event.key === "ArrowDown") {
      event.preventDefault();
      setActive((index) => Math.min(index + 1, items.length - 1));
    } else if (event.key === "ArrowUp") {
      event.preventDefault();
      setActive((index) => Math.max(index - 1, 0));
    } else if (event.key === "Enter" && items[active]) {
      event.preventDefault();
      choose(items[active]);
    }
  };

  return (
    <Modal
      open={open}
      onCancel={close}
      footer={null}
      closable={false}
      destroyOnHidden
      afterOpenChange={(visible) => visible && inputRef.current?.focus()}
      className="command-palette"
      width={560}
      title={null}
    >
      <Input
        ref={inputRef}
        value={query}
        onChange={(event) => setQuery(event.target.value)}
        onKeyDown={onKeyDown}
        placeholder="Jump to a project, workspace or page"
        aria-label="Search"
        aria-controls="command-palette-results"
        aria-activedescendant={items[active] ? optionId(items[active].key) : undefined}
        role="combobox"
        aria-expanded={items.length > 0}
        variant="borderless"
        size="large"
      />
      <ul id="command-palette-results" role="listbox" className="command-palette-results">
        {items.map((item, index) => (
          // The options are driven by the key handler on the combobox input (arrows and Enter), as in the ARIA
          // combobox pattern, so each option itself needs no key handler.
          // eslint-disable-next-line jsx-a11y/click-events-have-key-events
          <li
            key={item.key}
            id={optionId(item.key)}
            role="option"
            aria-selected={index === active}
            className={index === active ? "command-palette-item command-palette-item-active" : "command-palette-item"}
            onMouseEnter={() => setActive(index)}
            onClick={() => choose(item)}
          >
            <span>
              {item.title}
              {item.subtitle && <Typography.Text type="secondary"> {item.subtitle}</Typography.Text>}
            </span>
            <Typography.Text type="secondary" className="command-palette-kind">
              {KIND_LABEL[item.kind]}
            </Typography.Text>
          </li>
        ))}
      </ul>
      <div className="command-palette-status" role="status" aria-live="polite">
        {failed
          ? "Search is unavailable right now."
          : searching
            ? "Searching..."
            : query.trim() && items.length === 0
              ? organizations.length > MAX_ORGANIZATIONS_SEARCHED && !currentOrg
                ? "Open an organization to search its projects and workspaces."
                : `Nothing matches "${query.trim()}".`
              : ""}
      </div>
    </Modal>
  );
}

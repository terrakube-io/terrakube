import { Input, Modal, Typography } from "antd";
import { tinykeys } from "tinykeys";
import type { InputRef } from "antd";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { getOrgIdFromPathname } from "@/config/orgId";
import { confirmApprove } from "@/modules/inbox/confirmApprove";
import { findWaitingRuns } from "@/modules/projects/bulkWorkspaceActions";
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
  action: "Action",
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
    // Ctrl+K opens the palette from anywhere, even from a field. The sequences are for moving about, so tinykeys'
    // default applies to them: they do nothing while someone types in a field.
    const stopOpen = tinykeys(
      window,
      {
        "$mod+k": (event) => {
          event.preventDefault();
          if (openRef.current) close();
          else setOpen(true);
        },
      },
      { ignore: (event) => event.repeat || event.isComposing }
    );
    const go = (to: string | null) => () => {
      if (to) navigate(to);
    };
    const stopGo = tinykeys(window, {
      "g i": go("/inbox"),
      "g p": go(currentOrgId ? `/organizations/${currentOrgId}/projects` : null),
      "g w": go(currentOrgId ? `/organizations/${currentOrgId}/workspaces` : null),
    });
    const onOpen = () => setOpen(true);
    window.addEventListener(OPEN_PALETTE_EVENT, onOpen);
    return () => {
      stopOpen();
      stopGo();
      window.removeEventListener(OPEN_PALETTE_EVENT, onOpen);
    };
  }, [navigate, currentOrgId, close]);

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

  // The run waiting for approval on the workspace the selection is on, offered as an action under the results. The
  // lookup is per workspace and remembered, so moving along a list does not ask again for one already seen.
  const [focused, setFocused] = useState<PaletteItem | null>(null);
  const [waiting, setWaiting] = useState<Record<string, string | null>>({});

  const shown = useMemo<PaletteItem[]>(() => {
    const jobId = focused ? waiting[focused.key] : null;
    const match = focused?.to.match(/^\/organizations\/([^/]+)\/workspaces\/([^/]+)/);
    if (!focused || !jobId || !match) return items;
    const action: PaletteItem = {
      key: `action:approve:${jobId}`,
      kind: "action",
      title: `Approve run #${jobId}`,
      subtitle: focused.title,
      to: "",
      // Never straight away: the same question as in the inbox, the overview and the run list.
      run: async () =>
        confirmApprove(
          {
            key: `waiting:${match[2]}`,
            kind: "waiting",
            orgId: match[1],
            orgName: focused.subtitle ?? "",
            workspaceId: match[2],
            name: focused.title,
          },
          () => undefined
        ),
    };
    // Right under the workspace it belongs to, so one arrow down reaches it whatever else the search found.
    const at = items.findIndex((item) => item.key === focused.key);
    return at < 0 ? [...items, action] : [...items.slice(0, at + 1), action, ...items.slice(at + 1)];
  }, [items, focused, waiting]);

  useEffect(() => {
    const current = shown[active];
    if (current?.kind === "workspace") setFocused(current);
    else if (current?.kind !== "action") setFocused(null);
  }, [shown, active]);

  useEffect(() => {
    if (!open || !focused || focused.key in waiting) return;
    const match = focused.to.match(/^\/organizations\/([^/]+)\/workspaces\/([^/]+)/);
    if (!match) return;
    let alive = true;
    (async () => {
      let jobId: string | null = null;
      try {
        const runs = (await findWaitingRuns(match[1], [match[2]])) ?? [];
        jobId = runs[0]?.jobId ?? null;
      } catch {
        jobId = null;
      }
      if (alive) setWaiting((known) => ({ ...known, [focused.key]: jobId }));
    })();
    return () => {
      alive = false;
    };
  }, [open, focused, waiting]);

  useEffect(() => {
    if (!open) setWaiting({});
  }, [open]);

  useEffect(() => setActive(0), [query]);

  const choose = (item: PaletteItem) => {
    if (item.kind === "project" || item.kind === "workspace") rememberRecent(item);
    close();
    if (item.run) {
      item.run();
      return;
    }
    navigate(item.to);
  };

  const onKeyDown = (event: React.KeyboardEvent) => {
    if (event.key === "ArrowDown") {
      event.preventDefault();
      setActive((index) => Math.min(index + 1, shown.length - 1));
    } else if (event.key === "ArrowUp") {
      event.preventDefault();
      setActive((index) => Math.max(index - 1, 0));
    } else if (event.key === "Enter" && shown[active]) {
      event.preventDefault();
      choose(shown[active]);
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
        aria-activedescendant={shown[active] ? optionId(shown[active].key) : undefined}
        role="combobox"
        aria-expanded
        variant="borderless"
        size="large"
      />
      <ul id="command-palette-results" role="listbox" className="command-palette-results">
        {shown.map((item, index) => (
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
            : query.trim() && shown.length === 0
              ? `Nothing matches "${query.trim()}".`
              : ""}
      </div>
    </Modal>
  );
}

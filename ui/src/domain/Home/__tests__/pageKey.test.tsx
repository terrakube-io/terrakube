import { act, render, screen } from "@testing-library/react";
import { useEffect } from "react";
import { createMemoryRouter, Outlet, RouterProvider, useMatches, useParams } from "react-router-dom";
import { pageKey, workspacePage } from "../pageKey";

// jsdom has no fetch Request, which the data router builds on every navigation. These routes have no
// loaders, so the router only reads the URL and the abort signal.
if (typeof globalThis.Request === "undefined") {
  globalThis.Request = class {
    url: string;
    signal: AbortSignal;
    method = "GET";
    constructor(url: string, init?: { signal?: AbortSignal }) {
      this.url = url;
      this.signal = init?.signal ?? new AbortController().signal;
    }
  } as unknown as typeof Request;
}

// Mirrors AppLayout: the page under the layout is keyed by pageKey().
const Layout = () => (
  <div key={pageKey(useMatches())}>
    <Outlet />
  </div>
);

let mounts = 0;
const Page = ({ tab }: { tab: string }) => {
  const params = useParams();
  useEffect(() => {
    mounts += 1;
  }, []);
  return <p>{`${tab} ${JSON.stringify(params)}`}</p>;
};

const ws = "/organizations/:orgid/workspaces/:id";
const settings = "/organizations/:orgid/settings";

function setup(initial: string) {
  mounts = 0;
  const router = createMemoryRouter(
    [
      {
        path: "/",
        element: <Layout />,
        children: [
          { path: ws, element: <Page tab="overview" />, handle: workspacePage },
          { path: `${ws}/runs`, element: <Page tab="runs" />, handle: workspacePage },
          { path: `${ws}/runs/:runid`, element: <Page tab="run" />, handle: workspacePage },
          { path: `${ws}/variables`, element: <Page tab="variables" />, handle: workspacePage },
          { path: `${settings}/teams`, element: <Page tab="teams" /> },
          { path: `${settings}/teams/new`, element: <Page tab="teams" /> },
        ],
      },
    ],
    { initialEntries: [initial] }
  );
  render(<RouterProvider router={router} />);
  return (path: string) => act(() => router.navigate(path));
}

describe("pageKey", () => {
  it("keeps the workspace page mounted when only the tab changes", async () => {
    const go = setup("/organizations/o1/workspaces/w1/runs");
    await go("/organizations/o1/workspaces/w1/variables");
    await go("/organizations/o1/workspaces/w1");
    expect(screen.getByText(/^overview/)).toBeInTheDocument();
    expect(mounts).toBe(1);
  });

  it("remounts for another workspace, another organization or a run", async () => {
    const go = setup("/organizations/o1/workspaces/w1/runs");
    await go("/organizations/o1/workspaces/w2/runs");
    expect(mounts).toBe(2);
    await go("/organizations/o2/workspaces/w2/runs");
    expect(mounts).toBe(3);
    await go("/organizations/o2/workspaces/w2/runs/r1");
    expect(mounts).toBe(4);
  });

  it("remounts routes without a page handle on every route change", async () => {
    // e.g. saving a new team navigates back to the list, which must reload.
    const go = setup("/organizations/o1/settings/teams/new");
    await go("/organizations/o1/settings/teams");
    expect(mounts).toBe(2);
  });

  it("returns an empty key without matches", () => {
    expect(pageKey([])).toBe("");
  });
});

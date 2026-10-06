import { readFileSync } from "fs";
import { join } from "path";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import WorkspaceTagChips from "../WorkspaceTagChips";

const tags = [
  { id: "tag-1", name: "env" },
  { id: "tag-2", name: "team" },
  { id: "tag-3", name: "owner" },
];

const threeBindings = [
  { tagId: "tag-1", value: "prod" },
  { tagId: "tag-2", value: "infra" },
  { tagId: "tag-3", value: "alice" },
];

const toggle = () => screen.getByRole("button", { name: "Show 3 tags" });

describe("WorkspaceTagChips", () => {
  it("renders nothing when the workspace has no tags", () => {
    const { container } = render(<WorkspaceTagChips bindings={[]} tags={tags} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("shows key:value for a tag with a value and the key alone without one", () => {
    render(
      <WorkspaceTagChips
        bindings={[
          { tagId: "tag-1", value: "prod" },
          { tagId: "tag-2", value: null },
        ]}
        tags={tags}
      />
    );
    expect(screen.getByTitle("env = prod")).toBeInTheDocument();
    expect(screen.getByTitle("team")).toBeInTheDocument();
  });

  // What the workspace card did before tags carried a value, and it still has the room for it
  it("shows every tag and no button when not collapsed", () => {
    render(<WorkspaceTagChips bindings={threeBindings} tags={tags} />);

    expect(screen.getByTitle("env = prod")).toBeInTheDocument();
    expect(screen.getByTitle("owner = alice")).toBeInTheDocument();
    expect(screen.getByTitle("team = infra")).toBeInTheDocument();
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  // The database returns the bindings unordered, so without this the row's chips change under you
  it("orders the chips by key, then by value", () => {
    const { container } = render(
      <WorkspaceTagChips
        bindings={[
          { tagId: "tag-3", value: "alice" },
          { tagId: "tag-1", value: "prod" },
          { tagId: "tag-2", value: "infra" },
        ]}
        tags={tags}
      />
    );

    const labels = [...container.querySelectorAll(".workspace-tag-chip")].map((chip) => chip.textContent);
    expect(labels).toEqual(["env prod", "owner alice", "team infra"]);
  });

  it("collapsed, shows only an icon button that names the tag count", () => {
    render(<WorkspaceTagChips bindings={threeBindings} tags={tags} collapsed />);

    expect(toggle()).toHaveTextContent("");
    expect(screen.queryByTitle("env = prod")).not.toBeInTheDocument();
  });

  // Hovering is what a pointer reaches for first, so it has to work as well as clicking
  it("opens the tags on hover", async () => {
    const user = userEvent.setup();
    render(<WorkspaceTagChips bindings={threeBindings} tags={tags} collapsed />);

    await user.hover(toggle());

    await waitFor(() => expect(screen.getByTitle("team = infra")).toBeInTheDocument());
    expect(screen.getByTitle("owner = alice")).toBeInTheDocument();
  });

  it("opens the tags on click", async () => {
    const user = userEvent.setup();
    render(<WorkspaceTagChips bindings={threeBindings} tags={tags} collapsed />);

    await user.click(toggle());

    await waitFor(() => expect(screen.getByTitle("team = infra")).toBeInTheDocument());
  });

  // The list covers each row with a link, so the button cannot be reachable by pointer alone
  it("opens the tags from the keyboard", async () => {
    const user = userEvent.setup();
    render(<WorkspaceTagChips bindings={threeBindings} tags={tags} collapsed />);

    await user.tab();
    expect(toggle()).toHaveFocus();
    await user.keyboard("{Enter}");

    await waitFor(() => expect(screen.getByTitle("team = infra")).toBeInTheDocument());
  });

  it("lists every tag in the popover, under a count", async () => {
    const user = userEvent.setup();
    render(<WorkspaceTagChips bindings={threeBindings} tags={tags} collapsed />);

    await user.hover(toggle());

    await waitFor(() => expect(screen.getByText("Tags (3)")).toBeInTheDocument());
    expect(screen.getByTitle("env = prod")).toBeInTheDocument();
    expect(screen.getByTitle("team = infra")).toBeInTheDocument();
    expect(screen.getByTitle("owner = alice")).toBeInTheDocument();
  });

  // The list and the card both lay a positioned link over the whole row, and that link paints over
  // static siblings: without a stacking order of their own the chips end up under it and the button
  // cannot be reached at all. jsdom applies no stylesheet, so assert the rule itself.
  it("keeps the chips stacked above the row-wide overlay link", () => {
    const css = readFileSync(join(__dirname, "..", "WorkspaceTagChips.css"), "utf-8");
    const rule = css.match(/\.workspace-tag-chip,\s*\.workspace-tag-chip-toggle\s*\{([^}]*)\}/);
    expect(rule).not.toBeNull();
    expect(rule![1]).toMatch(/position:\s*relative/);
    expect(Number(rule![1].match(/z-index:\s*(\d+)/)?.[1])).toBeGreaterThan(0);
  });

  // The dark theme gives every filled tag a grey border at the same specificity as a stylesheet rule
  it("sets the border to the tag colour inline", () => {
    render(<WorkspaceTagChips bindings={[{ tagId: "tag-1", value: "prod" }]} tags={tags} />);

    const chip = screen.getByTitle("env = prod");
    expect(chip.style.borderColor).not.toBe("");
    expect(chip.style.getPropertyValue("--tag-color")).not.toBe("");
  });

  it("falls back to the tag id when the organization tag list has not loaded", () => {
    render(<WorkspaceTagChips bindings={[{ tagId: "tag-9", value: "x" }]} tags={[]} />);
    expect(screen.getByTitle("tag-9 = x")).toBeInTheDocument();
  });
});

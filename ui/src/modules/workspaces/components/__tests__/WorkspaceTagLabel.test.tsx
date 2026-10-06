import { render } from "@testing-library/react";
import WorkspaceTagLabel from "../WorkspaceTagLabel";

const tags = [{ id: "tag-1", name: "env" }];

describe("WorkspaceTagLabel", () => {
  // A real space rather than a margin, so selecting the chip copies "env prod" and not "envprod"
  it("renders the key and the value as separate elements, separated by a space", () => {
    const { container } = render(<WorkspaceTagLabel binding={{ tagId: "tag-1", value: "prod" }} tags={tags} />);

    expect(container.querySelector(".workspace-tag-label-key")).toHaveTextContent("env");
    expect(container.querySelector(".workspace-tag-label-value")).toHaveTextContent("prod");
    expect(container.textContent).toBe("env prod");
  });

  it("renders the key alone for a tag that carries no value", () => {
    const { container } = render(<WorkspaceTagLabel binding={{ tagId: "tag-1", value: null }} tags={tags} />);

    expect(container.textContent).toBe("env");
    expect(container.querySelector(".workspace-tag-label-value")).toBeNull();
  });
});

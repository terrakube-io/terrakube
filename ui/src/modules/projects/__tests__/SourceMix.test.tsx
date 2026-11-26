import { render, screen } from "@testing-library/react";
import { SourceMix } from "../SourceMix";

describe("SourceMix", () => {
  it("shows how many workspaces run from a repository and how many from the CLI", () => {
    render(<SourceMix vcs={76} cli={62} locked={5} />);
    expect(screen.getByText("76 VCS")).toBeInTheDocument();
    expect(screen.getByText("62 CLI")).toBeInTheDocument();
    expect(screen.getByText("5 locked")).toBeInTheDocument();
    expect(
      screen.getByRole("img", { name: "76 run from a repository, 62 driven from the CLI or API" })
    ).toBeInTheDocument();
  });

  it("leaves out the locked chip when nothing is locked and does not divide by zero", () => {
    render(<SourceMix vcs={0} cli={0} locked={0} />);
    expect(screen.queryByText(/locked/)).not.toBeInTheDocument();
    expect(screen.getByText("0 VCS")).toBeInTheDocument();
  });
});

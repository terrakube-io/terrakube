import { fireEvent, render, screen } from "@testing-library/react";
import { DIAGNOSTICS_SHOWN_AT_FIRST, RunDiagnostics } from "../RunDiagnostics";

describe("RunDiagnostics", () => {
  it("renders nothing when there are no diagnostics", () => {
    const { container } = render(<RunDiagnostics diagnostics={[]} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("lists each diagnostic with its resource and source location, and counts them", () => {
    render(
      <RunDiagnostics
        diagnostics={[
          { key: "a", severity: "error", summary: "Quota exceeded", address: "aws_instance.web", stepNames: ["Apply"] },
          {
            key: "b",
            severity: "warning",
            summary: "Deprecated variable",
            detail: "Use 'x'.",
            location: "main.tf:3",
            stepNames: ["Plan"],
          },
        ]}
      />
    );

    expect(screen.getByText("1 error")).toBeInTheDocument();
    expect(screen.getByText("1 warning")).toBeInTheDocument();
    expect(screen.getByText("aws_instance.web")).toBeInTheDocument();
    expect(screen.getByText(/Quota exceeded/)).toBeInTheDocument();
    expect(screen.getByText(/\(main\.tf:3\)/)).toBeInTheDocument();
    expect(screen.getByText("Use 'x'.")).toBeInTheDocument();
  });

  it("is open when there is an error, so it is seen without clicking", () => {
    render(
      <RunDiagnostics diagnostics={[{ key: "boom", severity: "error", summary: "Boom", stepNames: ["Apply"] }]} />
    );
    expect(screen.getAllByTestId("run-diagnostic")).toHaveLength(1);
  });

  it("names the steps that reported a diagnostic", () => {
    render(
      <RunDiagnostics diagnostics={[{ key: "a", severity: "error", summary: "Boom", stepNames: ["Plan", "Apply"] }]} />
    );

    expect(screen.getByTestId("run-diagnostic-steps")).toHaveTextContent("Reported by Plan, Apply");
  });

  describe("a run with very many diagnostics", () => {
    const many = Array.from({ length: DIAGNOSTICS_SHOWN_AT_FIRST + 20 }, (_, index) => ({
      key: `k${index}`,
      severity: "error" as const,
      summary: `Problem ${index}`,
      stepNames: ["Plan"],
    }));

    it("shows the first ones, with the total in the counts, and the rest on request", () => {
      render(<RunDiagnostics diagnostics={many} />);

      expect(screen.getByText(`${many.length} errors`)).toBeInTheDocument();
      expect(screen.getAllByTestId("run-diagnostic")).toHaveLength(DIAGNOSTICS_SHOWN_AT_FIRST);
      fireEvent.click(screen.getByTestId("run-diagnostics-show-all"));

      expect(screen.getAllByTestId("run-diagnostic")).toHaveLength(many.length);
      expect(screen.queryByTestId("run-diagnostics-show-all")).not.toBeInTheDocument();
    });

    it("does not offer to show more when everything is shown", () => {
      render(<RunDiagnostics diagnostics={many.slice(0, DIAGNOSTICS_SHOWN_AT_FIRST)} />);

      expect(screen.queryByTestId("run-diagnostics-show-all")).not.toBeInTheDocument();
    });
  });
});

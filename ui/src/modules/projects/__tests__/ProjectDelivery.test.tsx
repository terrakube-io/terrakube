import { render, screen, waitFor } from "@testing-library/react";
import { axe, toHaveNoViolations } from "jest-axe";
import ProjectDelivery from "../ProjectDelivery";
import projectService from "../projectService";

expect.extend(toHaveNoViolations);

jest.mock("../projectService", () => ({ __esModule: true, default: { getDelivery: jest.fn() } }));
const getDelivery = projectService.getDelivery as jest.Mock;

const delivery = (overrides = {}) => ({
  isError: false,
  data: {
    runs: 184,
    succeededPercent: 94,
    medianApproveMs: 22 * 60_000,
    medianRunMs: 41 * 60_000,
    partial: false,
    ...overrides,
  },
});

describe("ProjectDelivery", () => {
  beforeEach(() => jest.resetAllMocks());

  it("shows the four numbers for the project", async () => {
    getDelivery.mockResolvedValue(delivery());
    render(<ProjectDelivery orgid="org" projectId="p1" />);

    expect(await screen.findByText("184")).toBeInTheDocument();
    expect(screen.getByText("94%")).toBeInTheDocument();
    expect(screen.getByText("22 min")).toBeInTheDocument();
    expect(screen.getByText("41 min")).toBeInTheDocument();
    expect(getDelivery).toHaveBeenCalledWith("org", "p1", 30);
  });

  it("shows a dash, not a zero, where there is nothing to measure", async () => {
    getDelivery.mockResolvedValue(
      delivery({ runs: 2, succeededPercent: null, medianApproveMs: null, medianRunMs: null })
    );
    render(<ProjectDelivery orgid="org" projectId="p1" />);

    await screen.findByText("2");
    expect(screen.getAllByText("-")).toHaveLength(3);
  });

  it("says when it only read the latest runs", async () => {
    getDelivery.mockResolvedValue(delivery({ partial: true }));
    render(<ProjectDelivery orgid="org" projectId="p1" />);

    expect(await screen.findByText(/latest runs only/)).toBeInTheDocument();
  });

  it("shows a count that may have been cut short as at least that many", async () => {
    getDelivery.mockResolvedValue(delivery({ runs: 500, partial: true }));
    render(<ProjectDelivery orgid="org" projectId="p1" />);

    expect(await screen.findByText("500+")).toBeInTheDocument();
    expect(screen.queryByText("500")).not.toBeInTheDocument();
  });

  it("gives each figure its definition to the keyboard and to a screen reader, not only to the mouse", async () => {
    getDelivery.mockResolvedValue(delivery());
    render(<ProjectDelivery orgid="org" projectId="p1" />);
    await screen.findByText("184");

    const figure = screen.getByText("Median run time").closest(".overview-figure") as HTMLElement;
    expect(figure).toHaveAttribute("tabindex", "0");
    const described = document.getElementById(figure.getAttribute("aria-describedby") as string);
    expect(described).toHaveTextContent("From a run starting to it finishing, so it includes any wait for approval.");
  });

  it("says so when there were no runs, and when it could not be read", async () => {
    getDelivery.mockResolvedValue(delivery({ runs: 0 }));
    const { unmount } = render(<ProjectDelivery orgid="org" projectId="p1" />);
    expect(await screen.findByText("No runs in this time.")).toBeInTheDocument();
    unmount();

    getDelivery.mockResolvedValue({ isError: true });
    render(<ProjectDelivery orgid="org" projectId="p1" />);
    expect(await screen.findByText("Not available right now.")).toBeInTheDocument();
  });

  it("has no accessibility violations", async () => {
    getDelivery.mockResolvedValue(delivery());
    const { container } = render(<ProjectDelivery orgid="org" projectId="p1" />);
    await waitFor(() => screen.getByText("184"));

    expect(await axe(container)).toHaveNoViolations();
  });
});

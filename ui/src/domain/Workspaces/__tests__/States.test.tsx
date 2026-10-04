import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { useState } from "react";
import { MemoryRouter } from "react-router-dom";
import { States } from "../States";
import { FlatJobHistory, Workspace } from "../../types";

const apiGet = jest.fn();
const clientGet = jest.fn();

jest.mock("../../../config/axiosConfig", () => ({
  __esModule: true,
  default: { get: (...args: unknown[]) => apiGet(...args), put: jest.fn() },
  axiosClient: { get: (...args: unknown[]) => clientGet(...args) },
  getErrorMessage: (error: Error) => error.message,
}));
jest.mock("reactflow", () => ({
  __esModule: true,
  default: () => null,
  Background: () => null,
  Controls: () => null,
  MarkerType: { Arrow: "arrow" },
  applyEdgeChanges: jest.fn(),
  applyNodeChanges: jest.fn(),
}));
jest.mock("reactflow/dist/style.css", () => ({}));
jest.mock("../ResourceDrawer", () => ({ ResourceDrawer: () => null }));
jest.mock("../NodeResource", () => ({ __esModule: true, default: () => null }));
jest.mock("@/components/display/JsonViewer", () => ({
  __esModule: true,
  default: ({ value }: { value: unknown }) => <pre>{JSON.stringify(value)}</pre>,
}));

const version = (id: string, output: string, createdDate: string): FlatJobHistory =>
  ({
    id,
    title: `Version ${id}`,
    output,
    createdDate,
    relativeDate: "now",
    createdBy: "me",
    jobReference: "1",
  }) as FlatJobHistory;

// The parent owns the details flag; keep it in local state here.
const StatefulStates = ({ history }: { history: FlatJobHistory[] }) => {
  const [visible, setVisible] = useState(false);
  return (
    <MemoryRouter>
      <States
        history={history}
        stateDetailsVisible={visible}
        setStateDetailsVisible={setVisible}
        workspace={{ id: "ws-1" } as Workspace}
        onRollback={() => {}}
        manageState
      />
    </MemoryRouter>
  );
};

beforeEach(() => {
  apiGet.mockReset();
  clientGet.mockReset();
});

describe("States", () => {
  it("fetches the raw state and the previous version only from the Terrakube API", async () => {
    const api = "https://terrakube-api.test/tfstate/v1/organization/o/workspace/w/state";
    apiGet.mockImplementation((url: string) =>
      Promise.resolve({ data: url.endsWith(".raw.json") ? { serial: url.includes("/2.") ? 2 : 1 } : { values: {} } })
    );
    render(
      <StatefulStates
        history={[version("1", `${api}/1.json`, "2026-01-01"), version("2", `${api}/2.json?v=1`, "2026-01-02")]}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: "Version 2" }));

    await waitFor(() => expect(apiGet).toHaveBeenCalledWith(`${api}/2.raw.json?v=1`));
    expect(apiGet).toHaveBeenCalledWith(`${api}/1.raw.json`);
    expect(clientGet).not.toHaveBeenCalled();
    expect(screen.queryByText(/not available for this storage/)).not.toBeInTheDocument();
  });

  it("shows raw state and comparison as unavailable for other storage URLs", async () => {
    const storage = "https://bucket.blob.core.windows.net/tfstate";
    clientGet.mockResolvedValue({ data: { values: {} } });
    render(
      <StatefulStates
        history={[
          version("1", `${storage}/1.json?sv=2024&sig=abc.json`, "2026-01-01"),
          version("2", `${storage}/2.json?sv=2024&sig=abc.json`, "2026-01-02"),
        ]}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: "Version 2" }));

    expect(
      await screen.findAllByText("Raw state and version comparison are not available for this storage.")
    ).toHaveLength(1);
    expect(clientGet).toHaveBeenCalledTimes(1);
    expect(clientGet).toHaveBeenCalledWith(`${storage}/2.json?sv=2024&sig=abc.json`);
    expect(apiGet).not.toHaveBeenCalled();
  });
});

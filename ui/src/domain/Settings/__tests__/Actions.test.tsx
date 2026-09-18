import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { Buffer } from "buffer";
import axiosInstance from "@/config/axiosConfig";
import { ActionSettings } from "../Actions";

jest.mock("@/config/axiosConfig", () => ({
  __esModule: true,
  default: { get: jest.fn(), post: jest.fn(), patch: jest.fn() },
  getErrorMessage: (error: Error) => error.message,
}));

jest.mock("@/components/forms/CodeEditor", () => ({
  CodeEditor: ({ onMount }: { onMount: (editor: unknown) => void }) => {
    const React = jest.requireActual("react");
    const ref = React.useRef(null);
    React.useEffect(() => {
      onMount({
        getValue: () => ref.current.value,
        setValue: (value: string) => {
          ref.current.value = value;
        },
        focus: () => ref.current.focus(),
      });
    }, []);
    return <textarea aria-label="Action code" ref={ref} />;
  },
}));

const invalidCode = "() => { return (; }";
const validCode = "function(context) { return <Button>{context.name}</Button>; }";

beforeEach(() => {
  jest.clearAllMocks();
  jest.mocked(axiosInstance.get).mockImplementation(async (url) => ({
    data: {
      data:
        url === "action/example"
          ? {
              id: "example",
              attributes: {
                name: "Example",
                type: "Workspace/Action",
                category: "General",
                label: "Run",
                version: "1.0.0",
                displayCriteria: "[]",
                action: Buffer.from(invalidCode).toString("base64"),
              },
            }
          : [],
    },
  }));
  jest.mocked(axiosInstance.post).mockResolvedValue({ data: {} });
  jest.mocked(axiosInstance.patch).mockResolvedValue({ data: {} });
});

it.each(["new", "edit"] as const)("blocks invalid code and allows a corrected %s action", async (mode) => {
  render(
    <MemoryRouter>
      <ActionSettings editorMode={mode} editorId={mode === "edit" ? "example" : undefined} />
    </MemoryRouter>
  );
  if (mode === "new") {
    for (const [label, value] of [
      ["ID", "example"],
      ["Name", "Example"],
      ["Label", "Run"],
      ["Category", "General"],
      ["Version", "1.0.0"],
    ]) {
      fireEvent.change(screen.getByLabelText(label, { exact: true }), { target: { value } });
    }
    fireEvent.mouseDown(screen.getByRole("combobox"));
    fireEvent.click(await screen.findByText("Workspace/Action", { selector: ".ant-select-item-option-content" }));
    fireEvent.change(screen.getByLabelText("Action code"), { target: { value: invalidCode } });
  } else {
    await waitFor(() => expect(screen.getByLabelText("Action code")).toHaveValue(invalidCode));
  }

  fireEvent.click(screen.getByRole("button", { name: "Save", exact: true }));
  expect(await screen.findByText("Fix the action code before saving")).toBeInTheDocument();
  expect(axiosInstance.post).not.toHaveBeenCalled();
  expect(axiosInstance.patch).not.toHaveBeenCalled();
  expect(screen.getByLabelText("Action code")).toHaveValue(invalidCode);
  expect(screen.getByLabelText("Action code")).toHaveFocus();

  fireEvent.change(screen.getByLabelText("Action code"), { target: { value: validCode } });
  fireEvent.click(screen.getByRole("button", { name: "Save", exact: true }));
  const save = mode === "new" ? axiosInstance.post : axiosInstance.patch;
  await waitFor(() => expect(save).toHaveBeenCalledTimes(1));
  expect(jest.mocked(save).mock.calls[0][1]).toEqual(
    expect.objectContaining({
      data: expect.objectContaining({
        attributes: expect.objectContaining({ action: Buffer.from(validCode).toString("base64") }),
      }),
    })
  );
  expect(screen.queryByText("Fix the action code before saving")).not.toBeInTheDocument();
});

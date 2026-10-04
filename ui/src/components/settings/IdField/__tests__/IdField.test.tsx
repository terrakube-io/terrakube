import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { message } from "antd";
import IdField from "../IdField";

describe("IdField", () => {
  const writeText = jest.fn().mockResolvedValue(undefined);

  beforeEach(() => {
    writeText.mockClear();
    Object.assign(navigator, { clipboard: { writeText } });
  });

  it("renders the value in a read-only input labelled ID", () => {
    render(<IdField value="abc-123" />);
    const input = screen.getByLabelText("ID");
    expect(input).toHaveValue("abc-123");
    expect(input).toHaveAttribute("readonly");
  });

  it("copies the value and confirms with a toast", async () => {
    const success = jest.spyOn(message, "success").mockImplementation(jest.fn());
    render(<IdField value="abc-123" copiedMessage="Workspace ID copied" />);
    fireEvent.click(screen.getByRole("button", { name: "Copy ID" }));
    expect(writeText).toHaveBeenCalledWith("abc-123");
    await waitFor(() => expect(success).toHaveBeenCalledWith("Workspace ID copied"));
    success.mockRestore();
  });
});

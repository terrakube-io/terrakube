import { render, screen } from "@testing-library/react";
import DeleteConfirmationModal from "../DeleteConfirmationModal";

describe("DeleteConfirmationModal", () => {
  it("does not reveal the answer in the confirmation input placeholder", () => {
    render(
      <DeleteConfirmationModal
        open
        title="Delete this organization"
        message="Gone for good."
        confirmValue="acme"
        onConfirm={jest.fn()}
        onCancel={jest.fn()}
      />
    );
    const input = screen.getByLabelText("Type the name to confirm");
    expect(input).not.toHaveAttribute("placeholder");
    expect(input).toHaveValue("");
  });
});

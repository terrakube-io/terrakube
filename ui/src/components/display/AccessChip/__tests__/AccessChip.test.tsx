import { fireEvent, render, screen } from "@testing-library/react";
import AccessChip from "../AccessChip";
import { Capability } from "@/modules/permissions/accessModel";

const items: Capability[] = [
  { label: "View it", allowed: true },
  { label: "Approve runs", allowed: false },
];

describe("AccessChip", () => {
  it("names the role and the one team that grants it", () => {
    render(<AccessChip scope="project" level="write" teams={["devs"]} items={items} />);

    expect(screen.getByRole("button", { name: "Your access in this project" })).toHaveTextContent("Write · via devs");
  });

  it("names the place it is shown in", () => {
    render(<AccessChip scope="workspace" level="read" teams={[]} items={items} />);

    expect(screen.getByRole("button", { name: "Your access in this workspace" })).toHaveTextContent("Read");
  });

  it("counts the teams when several grant access, and explains the role is the highest", async () => {
    render(<AccessChip scope="organization" level="admin" teams={["a", "b", "c"]} items={items} />);

    const chip = screen.getByRole("button", { name: "Your access in this organization" });
    expect(chip).toHaveTextContent("Admin · via 3 teams");
    fireEvent.click(chip);
    expect(await screen.findByText(/You get the highest role across them/)).toBeInTheDocument();
  });

  it("calls a custom role Custom and shows what it contains", async () => {
    render(<AccessChip scope="project" level="custom" teams={["t"]} items={items} />);

    fireEvent.click(screen.getByRole("button", { name: "Your access in this project" }));

    expect(await screen.findByLabelText("Can: View it")).toBeInTheDocument();
    expect(screen.getByLabelText("Cannot: Approve runs")).toBeInTheDocument();
  });

  it("shows nothing before the access is known", () => {
    const { container } = render(<AccessChip scope="project" teams={[]} items={items} />);

    expect(container).toBeEmptyDOMElement();
  });
});

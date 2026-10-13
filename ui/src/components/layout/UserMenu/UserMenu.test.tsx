import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { UserMenu } from "./UserMenu";

const capabilitiesMock = jest.fn();

jest.mock("@/modules/cascades/cascadeService", () => ({
  getCascadeCapabilities: () => capabilitiesMock(),
}));
jest.mock("@/config/authConfig", () => ({ useAuth: () => ({ removeUser: jest.fn() }) }));
jest.mock("@/config/authUser", () => ({
  __esModule: true,
  default: () => ({ profile: { name: "Ada" } }),
}));
jest.mock("@/modules/utils/gravatar", () => ({ __esModule: true, default: () => Promise.resolve(undefined) }));

const openMenu = async () => {
  render(
    <MemoryRouter>
      <UserMenu />
    </MemoryRouter>
  );
  await userEvent.click(screen.getByRole("button", { name: "user menu" }));
};

describe("UserMenu", () => {
  beforeEach(() => capabilitiesMock.mockReset());

  it("offers instance administration only to instance admins", async () => {
    capabilitiesMock.mockResolvedValue({ operate: true, instanceAdmin: true });
    await openMenu();

    expect(await screen.findByText("Instance administration")).toBeInTheDocument();
  });

  it("does not show it to anyone else, or when the check fails", async () => {
    capabilitiesMock.mockResolvedValue({ operate: false, instanceAdmin: false });
    await openMenu();

    await screen.findByText("Account settings");
    await waitFor(() => expect(capabilitiesMock).toHaveBeenCalled());
    expect(screen.queryByText("Instance administration")).toBeNull();
  });
});

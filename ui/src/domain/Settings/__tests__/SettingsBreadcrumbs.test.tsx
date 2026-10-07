import { render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import axiosInstance from "../../../config/axiosConfig";
import { ORGANIZATION_ARCHIVE, ORGANIZATION_NAME } from "../../../config/actionTypes";
import { OrganizationSettings } from "../Settings";

jest.mock("../../../config/axiosConfig", () => ({
  __esModule: true,
  default: { get: jest.fn() },
}));
jest.mock("@/components/forms/CodeEditor", () => ({ CodeEditor: () => null }));
jest.mock("uuid", () => ({ v1: () => "uuid-1", v4: () => "uuid-4", v7: () => "uuid-7" }));
jest.mock("../PolicySets", () => ({ PolicySetsSettings: () => <div>policy sets</div> }));
jest.mock("../../../modules/permissions/useOrgPermissions", () => ({
  useOrgPermissions: () => ({ permissions: { managePermission: true, managePolicies: true } }),
}));

const ORG_ID = "70000000-0000-0000-0000-000000000001";

describe("OrganizationSettings breadcrumbs", () => {
  it("shows the organization from the URL, not the previously visited one", async () => {
    // A previous visit to another organization left its name behind.
    sessionStorage.setItem(ORGANIZATION_ARCHIVE, ORG_ID);
    sessionStorage.setItem(ORGANIZATION_NAME, "simple");
    (axiosInstance.get as jest.Mock).mockResolvedValue({
      data: { data: { id: ORG_ID, attributes: { name: "simple-governance" } } },
    });

    render(
      <MemoryRouter initialEntries={[`/organizations/${ORG_ID}/settings/policies`]}>
        <Routes>
          <Route path="/organizations/:orgid/settings/policies" element={<OrganizationSettings selectedTab="13" />} />
        </Routes>
      </MemoryRouter>
    );

    expect(screen.queryByText("simple")).not.toBeInTheDocument();
    expect(await screen.findByRole("link", { name: "simple-governance" })).toBeInTheDocument();
    await waitFor(() =>
      expect(axiosInstance.get).toHaveBeenCalledWith(`organization/${ORG_ID}?fields[organization]=name`)
    );
    expect(screen.queryByText("simple")).not.toBeInTheDocument();
  });
});

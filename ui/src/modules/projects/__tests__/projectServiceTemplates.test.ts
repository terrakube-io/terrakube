import axiosInstance from "@/config/axiosConfig";
import projectService from "../projectService";

jest.mock("@/config/axiosConfig", () => ({ __esModule: true, default: { get: jest.fn() } }));
jest.mock("@/modules/api/apiWrapper", () => ({ apiPost: jest.fn() }));

const get = axiosInstance.get as jest.Mock;
// The API serves a template's flow base64 encoded, as the template editor stores it.
const template = (id: string, flow: string) => ({ id, attributes: { name: id, tcl: btoa(flow) } });

describe("listOrganizationTemplates", () => {
  it("marks, from the real response, the templates that only plan", async () => {
    get.mockResolvedValue({
      data: {
        data: [
          template("plan", "flow:\n  - type: terraformPlan\n    step: 100\n"),
          template(
            "plans",
            "flow:\n  - type: terraformPlan\n    step: 100\n  - type: terraformPlanDestroy\n    step: 200\n"
          ),
          template("apply", "flow:\n  - type: terraformPlan\n    step: 100\n  - type: terraformApply\n    step: 200\n"),
          // A step that names its number before its type is still an apply.
          template(
            "apply-step-first",
            "flow:\n  - type: terraformPlan\n    step: 100\n  - step: 200\n    type: terraformApply\n"
          ),
          { id: "unreadable", attributes: { name: "unreadable", tcl: "not base64 %%%" } },
          { id: "empty", attributes: { name: "empty" } },
        ],
      },
    });

    const templates = await projectService.listOrganizationTemplates("org");

    expect(Object.fromEntries(templates.map((item) => [item.id, item.planOnly]))).toEqual({
      plan: true,
      plans: true,
      apply: false,
      "apply-step-first": false,
      unreadable: false,
      empty: false,
    });
  });
});

import { JobStatus } from "../../types";
import { shouldStepBeCollapsible, shouldStepBeExpandedByDefault } from "../stepExpansion";

const buildStep = (name: string, status: JobStatus) => {
  return {
    name,
    status,
  };
};

describe("shouldStepBeExpandedByDefault", () => {
  it("renders pending approval steps as non-collapsible rows", () => {
    expect(shouldStepBeCollapsible(buildStep("manual approval pending", JobStatus.WaitingApproval))).toBe(false);
    expect(shouldStepBeCollapsible(buildStep("Approve Plan from Terraform CLI", JobStatus.Pending))).toBe(false);
    expect(shouldStepBeExpandedByDefault(buildStep("manual approval pending", JobStatus.WaitingApproval))).toBe(false);
  });

  it("keeps apply pending collapsed until apply starts", () => {
    expect(shouldStepBeExpandedByDefault(buildStep("Apply", JobStatus.Pending))).toBe(false);
    expect(shouldStepBeExpandedByDefault(buildStep("Terraform Apply from Terraform CLI", JobStatus.NotExecuted))).toBe(
      false
    );
    expect(shouldStepBeExpandedByDefault(buildStep("Apply", JobStatus.Running))).toBe(true);
  });

  it("keeps approval steps collapsed by default even after they can be opened", () => {
    expect(shouldStepBeCollapsible(buildStep("Approve Plan from Terraform CLI", JobStatus.Completed))).toBe(true);
    expect(shouldStepBeExpandedByDefault(buildStep("Approve Plan from Terraform CLI", JobStatus.Completed))).toBe(
      false
    );
  });

  it("does not affect other step names", () => {
    expect(shouldStepBeExpandedByDefault(buildStep("terraform plan", JobStatus.Pending))).toBe(true);
  });
});

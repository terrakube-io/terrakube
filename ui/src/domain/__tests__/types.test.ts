import { formatJobVia, JobVia } from "../types";

describe("formatJobVia", () => {
  it("formats known VCS providers and run sources properly", () => {
    expect(formatJobVia(JobVia.Github)).toBe("GitHub");
    expect(formatJobVia("Github")).toBe("GitHub");
    expect(formatJobVia(JobVia.Gitlab)).toBe("GitLab");
    expect(formatJobVia("Gitlab")).toBe("GitLab");
    expect(formatJobVia("GitLab")).toBe("GitLab");
    expect(formatJobVia(JobVia.Bitbucket)).toBe("Bitbucket");
    expect(formatJobVia("Bitbucket")).toBe("Bitbucket");
    expect(formatJobVia(JobVia.AzureDevops)).toBe("Azure DevOps");
    expect(formatJobVia("AzureDevops")).toBe("Azure DevOps");
    expect(formatJobVia("Azure DevOps")).toBe("Azure DevOps");
    expect(formatJobVia(JobVia.Cli)).toBe("CLI");
    expect(formatJobVia("CLI")).toBe("CLI");
    expect(formatJobVia(JobVia.Schedule)).toBe("Schedule");
    expect(formatJobVia("Schedule")).toBe("Schedule");
    expect(formatJobVia(JobVia.Ui)).toBe("UI");
    expect(formatJobVia("UI")).toBe("UI");
  });

  it("falls back to UI when input is undefined or empty", () => {
    expect(formatJobVia(undefined)).toBe("UI");
    expect(formatJobVia("")).toBe("UI");
  });

  it("passes through unknown strings", () => {
    expect(formatJobVia("CustomSource")).toBe("CustomSource");
  });
});

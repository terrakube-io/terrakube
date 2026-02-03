/**
 * Utility to parse Terraform logs and split them into logical sections
 */

export interface LogSection {
  id: string;
  title: string;
  content: string;
  startMarker: RegExp;
  endMarker?: RegExp;
}

export interface ParsedLogs {
  sections: Array<{
    id: string;
    title: string;
    content: string;
  }>;
  rawContent: string;
}

/**
 * Parse terraform plan logs into sections:
 * - Init
 * - Resource scanning/refreshing
 * - Plan output
 */
export function parsePlanLogs(logs: string): ParsedLogs {
  const sections: Array<{ id: string; title: string; content: string }> = [];

  // Define section markers with more comprehensive patterns
  const initSection = extractSection(
    logs,
    /^(Initializing|terraform init|Initializing the backend|Initializing provider plugins)/im,
    /^(Terraform has been successfully initialized|Terraform initialization complete)/im
  );

  const scanningSection = extractSection(
    logs,
    /^(Refreshing state|Reading\.\.\.|data\.[a-zA-Z0-9_]+\.|Planning|Building|Acquiring state lock|Refreshing Terraform state)/im,
    /^(Terraform will perform|No changes\.|Plan:|Terraform used the selected providers|Note: Objects have changed)/im
  );

  const planSection = extractSection(
    logs,
    /^(Terraform will perform|No changes\.|Plan:|Terraform used the selected providers|Note: Objects have changed)/im,
    null // Goes until the end
  );

  if (initSection) {
    sections.push({
      id: "init",
      title: "Terraform Init",
      content: initSection,
    });
  }

  if (scanningSection) {
    sections.push({
      id: "scanning",
      title: "Resource Scanning & State Refresh",
      content: scanningSection,
    });
  }

  if (planSection) {
    sections.push({
      id: "plan",
      title: "Plan Output",
      content: planSection,
    });
  }

  // If no sections were found, return the entire log as a single section
  if (sections.length === 0) {
    sections.push({
      id: "full",
      title: "Full Output",
      content: logs,
    });
  }

  return {
    sections,
    rawContent: logs,
  };
}

/**
 * Parse terraform apply logs into sections:
 * - Init (if present)
 * - Resource preparation
 * - Apply output
 */
export function parseApplyLogs(logs: string): ParsedLogs {
  const sections: Array<{ id: string; title: string; content: string }> = [];

  // Define section markers with more comprehensive patterns
  const initSection = extractSection(
    logs,
    /^(Initializing|terraform init|Initializing the backend|Initializing provider plugins)/im,
    /^(Terraform has been successfully initialized|Terraform initialization complete)/im
  );

  const preparationSection = extractSection(
    logs,
    /^(Refreshing state|Reading\.\.\.|data\.[a-zA-Z0-9_]+\.|Acquiring state lock|Preparing|Building|Refreshing Terraform state|Planning changes)/im,
    /^(Apply complete|Applying|Creating|Modifying|Destroying|Apply started)/im
  );

  const applySection = extractSection(
    logs,
    /^(Apply complete|Applying|Creating|Modifying|Destroying|Apply started)/im,
    null // Goes until the end
  );

  if (initSection) {
    sections.push({
      id: "init",
      title: "Terraform Init",
      content: initSection,
    });
  }

  if (preparationSection) {
    sections.push({
      id: "preparation",
      title: "Resource Preparation & State Refresh",
      content: preparationSection,
    });
  }

  if (applySection) {
    sections.push({
      id: "apply",
      title: "Apply Output",
      content: applySection,
    });
  }

  // If no sections were found, return the entire log as a single section
  if (sections.length === 0) {
    sections.push({
      id: "full",
      title: "Full Output",
      content: logs,
    });
  }

  return {
    sections,
    rawContent: logs,
  };
}

/**
 * Extract a section from logs based on start and end markers
 */
function extractSection(
  logs: string,
  startMarker: RegExp,
  endMarker: RegExp | null
): string | null {
  const lines = logs.split("\n");
  let capturing = false;
  let sectionLines: string[] = [];

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];

    if (!capturing && startMarker.test(line)) {
      capturing = true;
      sectionLines.push(line);
      continue;
    }

    if (capturing) {
      if (endMarker && endMarker.test(line)) {
        // Include the end marker line and stop
        sectionLines.push(line);
        break;
      }
      sectionLines.push(line);
    }
  }

  // Clean up: Remove leading/trailing empty lines
  while (sectionLines.length > 0 && sectionLines[0].trim() === "") {
    sectionLines.shift();
  }
  while (sectionLines.length > 0 && sectionLines[sectionLines.length - 1].trim() === "") {
    sectionLines.pop();
  }

  return sectionLines.length > 0 ? sectionLines.join("\n") : null;
}

/**
 * Determine if a step is a plan or apply step based on step name or number
 */
export function getStepType(stepName: string, stepNumber: number): "plan" | "apply" | "other" {
  const nameLower = stepName.toLowerCase();

  if (nameLower.includes("plan") && !nameLower.includes("apply")) {
    return "plan";
  }

  if (nameLower.includes("apply")) {
    return "apply";
  }

  // Based on common step numbers
  if (stepNumber === 100) {
    return "plan";
  }

  if (stepNumber === 200) {
    return "apply";
  }

  return "other";
}

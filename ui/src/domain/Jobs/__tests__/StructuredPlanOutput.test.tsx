import { fireEvent, render, screen } from "@testing-library/react";
import { renderToStaticMarkup } from "react-dom/server";
import { StructuredPlanOutput } from "../StructuredPlanOutput";

describe("StructuredPlanOutput", () => {
  it("renders a success-toned empty state when there are no changes", () => {
    render(<StructuredPlanOutput changes={[]} />);

    expect(
      screen.getByText("Your infrastructure matches the configuration — no changes needed.")
    ).toBeInTheDocument();
  });

  it("surfaces diagnostics instead of a false success state when a failed plan produced no changes", () => {
    render(
      <StructuredPlanOutput
        changes={[]}
        jobDiagnostics={[{ severity: "error", summary: "Failed to initialize backend", detail: "connection refused" }]}
      />
    );

    expect(
      screen.queryByText("Your infrastructure matches the configuration — no changes needed.")
    ).not.toBeInTheDocument();
    expect(
      screen.getByText("Failed to initialize backend", { selector: ".structured-plan-jobDiagnostic--error span" })
    ).toBeInTheDocument();
  });

  it("renders a normalized replacement label", () => {
    render(
      <StructuredPlanOutput
        changes={[
          {
            address: "aws_instance.example",
            action: "replace",
            actions: ["delete", "create"],
            before: {
              instance_type: "t3.small",
            },
            after: {
              instance_type: "t3.medium",
            },
          },
        ]}
      />
    );

    expect(screen.getByText("1 to create")).toBeInTheDocument();
    expect(screen.getByText("1 to destroy")).toBeInTheDocument();
    expect(screen.getByText("Actions: 0 to invoke")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /aws_instance\.example/i }));

    expect(screen.getByText("replace")).toBeInTheDocument();
    expect(screen.getByText("aws_instance.example")).toBeInTheDocument();
    expect(screen.getByText("instance_type")).toBeInTheDocument();
    expect(screen.getByText('"t3.small"')).toBeInTheDocument();
    expect(screen.getByText('"t3.medium"')).toBeInTheDocument();
  });

  it("expands to reveal unchanged attributes on demand and collapses again", () => {
    render(
      <StructuredPlanOutput
        changes={[
          {
            address: "aws_instance.example",
            action: "update",
            actions: ["update"],
            before: {
              instance_type: "t3.small",
              ami: "ami-12345",
            },
            after: {
              instance_type: "t3.medium",
              ami: "ami-12345",
            },
          },
        ]}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: /aws_instance\.example/i }));

    expect(screen.getByText("Show 1 unchanged attribute")).toBeInTheDocument();
    expect(screen.queryByText("ami")).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Show 1 unchanged attribute" }));

    expect(screen.getByText("ami")).toBeInTheDocument();
    expect(screen.getByText('"ami-12345"')).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Hide unchanged attributes" })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Hide unchanged attributes" }));

    expect(screen.queryByText("ami")).not.toBeInTheDocument();
    expect(screen.getByText("Show 1 unchanged attribute")).toBeInTheDocument();
  });

  it("filters rows by multiple selected actions with counts shown", () => {
    render(
      <StructuredPlanOutput
        changes={[
          {
            address: "aws_instance.create_me",
            action: "create",
            actions: ["create"],
            after: { name: "new" },
          },
          {
            address: "aws_instance.update_me",
            action: "update",
            actions: ["update"],
            before: { name: "old" },
            after: { name: "new" },
          },
          {
            address: "aws_instance.delete_me",
            action: "delete",
            actions: ["delete"],
            before: { name: "old" },
          },
        ]}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: /filter by operation/i }));

    expect(screen.getByText("Create (1)")).toBeInTheDocument();
    expect(screen.getByText("Change (1)")).toBeInTheDocument();
    expect(screen.getByText("Destroy (1)")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("checkbox", { name: /Create \(1\)/i }));
    fireEvent.click(screen.getByRole("checkbox", { name: /Destroy \(1\)/i }));

    expect(screen.getByRole("button", { name: /aws_instance\.create_me/i })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /aws_instance\.delete_me/i })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /aws_instance\.update_me/i })).not.toBeInTheDocument();
  });

  it("shows a live per-resource status badge and progress summary in apply mode", () => {
    render(
      <StructuredPlanOutput
        applyMode
        changes={[
          {
            address: "aws_instance.applied",
            action: "create",
            actions: ["create"],
            after: { id: "i-1" },
            status: "applied",
          },
          {
            address: "aws_instance.applying",
            action: "update",
            actions: ["update"],
            before: { name: "old" },
            after: { name: "new" },
            status: "applying",
          },
        ]}
      />
    );

    expect(screen.getByText("1 of 2 applied")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /aws_instance\.applying/i }));
    expect(screen.getByText("modifying", { selector: ".structured-plan-applyStatus" })).toBeInTheDocument();
  });

  it("labels the apply-status badge for the resource's action, not a generic applied/errored", () => {
    render(
      <StructuredPlanOutput
        applyMode
        changes={[
          {
            address: "aws_instance.destroy_me",
            action: "delete",
            actions: ["delete"],
            before: { id: "i-1" },
            status: "applied",
          },
          {
            address: "aws_instance.destroy_failed",
            action: "delete",
            actions: ["delete"],
            before: { id: "i-2" },
            status: "errored",
          },
          {
            address: "random_string.imported_example",
            action: "import",
            actions: ["no-op"],
            after: { id: "abc" },
            importing: { id: "abc" },
            status: "applied",
          },
        ]}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: /aws_instance\.destroy_me/i }));
    fireEvent.click(screen.getByRole("button", { name: /aws_instance\.destroy_failed/i }));
    fireEvent.click(screen.getByRole("button", { name: /random_string\.imported_example/i }));

    expect(screen.getByText("destroyed", { selector: ".structured-plan-applyStatus" })).toBeInTheDocument();
    expect(screen.getByText("destroy failed", { selector: ".structured-plan-applyStatus" })).toBeInTheDocument();
    expect(screen.getByText("imported", { selector: ".structured-plan-applyStatus" })).toBeInTheDocument();
  });

  it("surfaces the diagnostic error message as a tooltip on the errored badge", () => {
    render(
      <StructuredPlanOutput
        applyMode
        changes={[
          {
            address: "null_resource.fails",
            action: "create",
            actions: ["create"],
            after: {},
            status: "errored",
            diagnostics: [{ severity: "error", summary: "local-exec provisioner error" }],
          },
        ]}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: /null_resource\.fails/i }));

    expect(screen.getByText("create failed", { selector: ".structured-plan-applyStatus" })).toHaveAttribute(
      "title",
      "local-exec provisioner error"
    );
  });

  it("filters rows by address", () => {
    render(
      <StructuredPlanOutput
        changes={[
          {
            address: "aws_instance.example",
            action: "update",
            actions: ["update"],
            before: {
              instance_type: "t3.small",
            },
            after: {
              instance_type: "t3.medium",
            },
          },
          {
            address: "railway_service.img",
            action: "update",
            actions: ["update"],
            before: {
              name: "img",
            },
            after: {
              name: "img-api",
            },
          },
        ]}
      />
    );

    fireEvent.change(screen.getByPlaceholderText("Filter resources by address..."), {
      target: { value: "railway" },
    });

    expect(screen.queryByRole("button", { name: /aws_instance\.example/i })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: /railway_service\.img/i })).toBeInTheDocument();
  });

  it("can show data sources when requested", () => {
    render(
      <StructuredPlanOutput
        changes={[
          {
            address: "aws_instance.example",
            action: "update",
            actions: ["update"],
            before: {
              instance_type: "t3.small",
            },
            after: {
              instance_type: "t3.medium",
            },
          },
          {
            address: "data.aws_caller_identity.current",
            action: "read",
            actions: ["read"],
            before: {
              account_id: "123456789012",
            },
            after: {
              account_id: "123456789012",
            },
          },
        ]}
      />
    );

    expect(screen.queryByRole("button", { name: /data\.aws_caller_identity\.current/i })).not.toBeInTheDocument();

    fireEvent.click(screen.getByLabelText(/show data sources/i));

    expect(screen.getByRole("button", { name: /data\.aws_caller_identity\.current/i })).toBeInTheDocument();
  });

  it("hides data sources on the initial render when managed resources are also present", () => {
    const html = renderToStaticMarkup(
      <StructuredPlanOutput
        changes={[
          {
            address: "aws_instance.example",
            action: "update",
            actions: ["update"],
            before: {
              instance_type: "t3.small",
            },
            after: {
              instance_type: "t3.medium",
            },
          },
          {
            address: "data.aws_caller_identity.current",
            action: "read",
            actions: ["read"],
            before: {
              account_id: "123456789012",
            },
            after: {
              account_id: "123456789012",
            },
          },
        ]}
      />
    );

    expect(html).toContain("aws_instance.example");
    expect(html).not.toContain("data.aws_caller_identity.current");
  });

  it("shows hidden unchanged attributes instead of dumping JSON", () => {
    render(
      <StructuredPlanOutput
        changes={[
          {
            address: "railway_service.img",
            action: "update",
            actions: ["update"],
            before: {
              name: "img",
              regions: {
                num_replicas: 2,
              },
            },
            after: {
              name: "img",
              regions: {
                num_replicas: 1,
              },
            },
          },
        ]}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: /railway_service\.img/i }));

    expect(screen.getByText(/unchanged attribute/i)).toBeInTheDocument();
    expect(screen.queryByText('"after"')).not.toBeInTheDocument();
  });

  it("hides unchanged sensitive attributes when they only carry sensitivity metadata", () => {
    render(
      <StructuredPlanOutput
        changes={[
          {
            address: "aws_secretsmanager_secret_version.example",
            action: "update",
            actions: ["update"],
            before: {
              secret_string: null,
            },
            beforeSensitive: {
              secret_string: true,
            },
            after: {
              secret_string: null,
            },
            afterSensitive: {
              secret_string: true,
            },
          },
        ]}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: /aws_secretsmanager_secret_version\.example/i }));

    expect(screen.getByText("No visible attribute changes.")).toBeInTheDocument();
    expect(screen.queryByText("secret_string")).not.toBeInTheDocument();
  });

  it("shows changed sensitive attributes when changed metadata is present", () => {
    render(
      <StructuredPlanOutput
        changes={[
          {
            address: "aws_secretsmanager_secret_version.example",
            action: "update",
            actions: ["update"],
            before: {
              secret_string: null,
            },
            beforeSensitive: {
              secret_string: true,
            },
            changedSensitive: {
              secret_string: true,
            },
            after: {
              secret_string: null,
            },
            afterSensitive: {
              secret_string: true,
            },
          },
        ]}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: /aws_secretsmanager_secret_version\.example/i }));

    expect(screen.getByText("secret_string")).toBeInTheDocument();
    expect(screen.getAllByText("sensitive value")).toHaveLength(2);
  });

  it("treats a null changedSensitive marker like a missing legacy marker", () => {
    render(
      <StructuredPlanOutput
        changes={[
          {
            address: "aws_secretsmanager_secret_version.example",
            action: "update",
            actions: ["update"],
            before: {
              secret_string: null,
            },
            beforeSensitive: {
              secret_string: true,
            },
            changedSensitive: null,
            after: {
              secret_string: "known after sanitization drift",
            },
            afterSensitive: {
              secret_string: true,
            },
          },
        ]}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: /aws_secretsmanager_secret_version\.example/i }));

    expect(screen.getByText("secret_string")).toBeInTheDocument();
    expect(screen.getAllByText("sensitive value")).toHaveLength(2);
  });

  it("expands array items so useful child fields are visible", () => {
    render(
      <StructuredPlanOutput
        changes={[
          {
            address: "railway_variable_collection.img",
            action: "update",
            actions: ["update"],
            before: {
              variables: [
                {
                  name: "CONSUMER_COUNT",
                  value: "1",
                },
                {
                  name: "UNCHANGED",
                  value: "same",
                },
              ],
            },
            after: {
              variables: [
                {
                  name: "CONSUMER_COUNT",
                  value: "2",
                },
                {
                  name: "UNCHANGED",
                  value: "same",
                },
              ],
            },
          },
        ]}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: /railway_variable_collection\.img/i }));

    expect(screen.getByText("variables")).toBeInTheDocument();
    expect(screen.getByText("[0] CONSUMER_COUNT")).toBeInTheDocument();
    expect(screen.queryByText(/\[1\] UNCHANGED/i)).not.toBeInTheDocument();
    expect(screen.queryByText('"CONSUMER_COUNT"')).not.toBeInTheDocument();
    expect(screen.getByText('"1"')).toBeInTheDocument();
    expect(screen.getByText('"2"')).toBeInTheDocument();
    expect(screen.getAllByText(/unchanged attribute/i).length).toBeGreaterThan(0);
  });

  it("shows an import's attributes expanded by default instead of a no-changes empty state", () => {
    render(
      <StructuredPlanOutput
        changes={[
          {
            address: "random_string.imported_example",
            action: "import",
            actions: ["no-op"],
            importing: { id: "AbcXyz1234567890" },
            before: { id: "AbcXyz1234567890", length: 16 },
            after: { id: "AbcXyz1234567890", length: 16 },
          },
        ]}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: /random_string\.imported_example/i }));

    expect(screen.queryByText("No visible attribute changes.")).not.toBeInTheDocument();
    expect(screen.getByText("id")).toBeInTheDocument();
    expect(screen.getByText('"AbcXyz1234567890"')).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Hide unchanged attributes" })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Hide unchanged attributes" }));

    expect(screen.queryByText('"AbcXyz1234567890"')).not.toBeInTheDocument();
    expect(screen.getByText("No visible attribute changes.")).toBeInTheDocument();
  });

  it("renders an Outputs section in apply mode, masking sensitive values", () => {
    render(
      <StructuredPlanOutput
        applyMode
        changes={[
          {
            address: "random_password.example",
            action: "create",
            actions: ["create"],
            after: { id: "abc" },
            status: "applied",
          },
        ]}
        outputs={[
          { name: "random_value", value: "sad-otter", sensitive: false },
          { name: "random_password_result", value: null, sensitive: true },
        ]}
      />
    );

    expect(screen.getByText("Outputs")).toBeInTheDocument();
    expect(screen.getByText("random_value")).toBeInTheDocument();
    // Unquoted, unlike a diff row's value token - output values are meant to be read/copied as-is,
    // not viewed as a compact attribute diff.
    expect(screen.getByText("sad-otter")).toBeInTheDocument();
    expect(screen.getByText("random_password_result")).toBeInTheDocument();
    expect(screen.getByText("sensitive value")).toBeInTheDocument();
  });

  it("shows the full output value untruncated even when long, with the same text available on hover", () => {
    const longValue = "a".repeat(120);

    render(
      <StructuredPlanOutput
        applyMode
        changes={[
          { address: "random_pet.this", action: "create", actions: ["create"], after: { id: "abc" }, status: "applied" },
        ]}
        outputs={[{ name: "long_output", value: longValue, sensitive: false }]}
      />
    );

    const valueEl = screen.getByText(longValue);
    expect(valueEl).toBeInTheDocument();
    expect(valueEl).toHaveAttribute("title", longValue);
  });

  it("shows a spinning indicator on the badge for an in-progress apply status", () => {
    render(
      <StructuredPlanOutput
        applyMode
        changes={[
          { address: "random_pet.this", action: "create", actions: ["create"], status: "applying" },
          { address: "random_pet.that", action: "create", actions: ["create"], status: "applied" },
        ]}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: /random_pet\.this/i }));
    fireEvent.click(screen.getByRole("button", { name: /random_pet\.that/i }));

    const badges = document.querySelectorAll(".structured-plan-applyStatus");
    const applyingBadge = Array.from(badges).find((badge) => badge.textContent?.includes("creating"));
    const appliedBadge = Array.from(badges).find((badge) => badge.textContent?.includes("created"));

    expect(applyingBadge?.querySelector(".structured-plan-applyStatusSpinner")).toBeInTheDocument();
    expect(appliedBadge?.querySelector(".structured-plan-applyStatusSpinner")).not.toBeInTheDocument();
  });

  it("shows a destroy/create dot pair on a replaced badge, not on a plain applied badge", () => {
    render(
      <StructuredPlanOutput
        applyMode
        changes={[
          {
            address: "random_pet.this",
            action: "replace",
            actions: ["delete", "create"],
            before: {},
            after: {},
            status: "applied",
          },
          { address: "random_pet.that", action: "create", actions: ["create"], status: "applied" },
        ]}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: /random_pet\.this/i }));
    fireEvent.click(screen.getByRole("button", { name: /random_pet\.that/i }));

    const badges = document.querySelectorAll(".structured-plan-applyStatus");
    const replacedBadge = Array.from(badges).find((badge) => badge.textContent?.includes("replaced"));
    const createdBadge = Array.from(badges).find((badge) => badge.textContent?.includes("created"));

    const dots = replacedBadge?.querySelectorAll(".structured-plan-applyStatusDot");
    expect(dots).toHaveLength(2);
    expect(replacedBadge?.querySelector(".structured-plan-applyStatusDot--destroy")).toBeInTheDocument();
    expect(replacedBadge?.querySelector(".structured-plan-applyStatusDot--create")).toBeInTheDocument();
    expect(createdBadge?.querySelector(".structured-plan-applyStatusDots")).not.toBeInTheDocument();
  });

  it("shows a plan-time 'will replace' indicator for a replace action, without applyMode", () => {
    render(
      <StructuredPlanOutput
        changes={[
          {
            address: "random_pet.this",
            action: "replace",
            actions: ["delete", "create"],
            before: { length: 6 },
            after: { length: 3 },
          },
          { address: "random_pet.that", action: "create", actions: ["create"], after: {} },
        ]}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: /random_pet\.this/i }));
    fireEvent.click(screen.getByRole("button", { name: /random_pet\.that/i }));

    expect(screen.getByText("will replace")).toBeInTheDocument();
    expect(screen.queryByText("will replace", { selector: ".structured-plan-applyStatus--replaced" })).not.toBeNull();
  });

  it("does not show the plan-time 'will replace' indicator once an apply-status badge takes over", () => {
    render(
      <StructuredPlanOutput
        applyMode
        changes={[
          {
            address: "random_pet.this",
            action: "replace",
            actions: ["delete", "create"],
            before: {},
            after: {},
            status: "pending",
          },
        ]}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: /random_pet\.this/i }));

    expect(screen.queryByText("will replace")).not.toBeInTheDocument();
    expect(screen.getByText("pending", { selector: ".structured-plan-applyStatus" })).toBeInTheDocument();
  });

  it("shows operation-filter counts that match what checking each box actually reveals", () => {
    // 1 pure create, 1 pure destroy, 2 replaces. summary.create/delete each count a replace too
    // (matching the top summary bar's Terraform-native convention), but the filter checkbox below
    // matches each row's own single action strictly - so "Create"/"Destroy" must show only the
    // pure counts (1 each), not 1+2=3, and "Replace" must show its own count (2), not
    // summary.create + summary.delete (which would wrongly total 6).
    render(
      <StructuredPlanOutput
        changes={[
          { address: "random_pet.pure_create", action: "create", actions: ["create"], after: {} },
          { address: "random_pet.pure_destroy", action: "delete", actions: ["delete"], before: {} },
          {
            address: "random_pet.replace_one",
            action: "replace",
            actions: ["delete", "create"],
            before: {},
            after: {},
          },
          {
            address: "random_pet.replace_two",
            action: "replace",
            actions: ["delete", "create"],
            before: {},
            after: {},
          },
        ]}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: /filter by operation/i }));

    expect(screen.getByText("Create (1)")).toBeInTheDocument();
    expect(screen.getByText("Destroy (1)")).toBeInTheDocument();
    expect(screen.getByText("Replace (2)")).toBeInTheDocument();
  });

  it("excludes no-op resources from the row list entirely, matching terraform plan's own CLI output", () => {
    render(
      <StructuredPlanOutput
        applyMode
        changes={[
          { address: "random_pet.untouched", action: "no-op", actions: ["no-op"], status: "applied" },
          { address: "random_pet.created", action: "create", actions: ["create"], after: {}, status: "applied" },
        ]}
      />
    );

    expect(screen.queryByRole("button", { name: /random_pet\.untouched/i })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: /random_pet\.created/i })).toBeInTheDocument();
  });

  it("does not render an Outputs section when there are no outputs", () => {
    render(
      <StructuredPlanOutput
        applyMode
        changes={[
          {
            address: "random_pet.this",
            action: "create",
            actions: ["create"],
            after: { id: "abc" },
            status: "applied",
          },
        ]}
      />
    );

    expect(screen.queryByText("Outputs")).not.toBeInTheDocument();
  });

  it("renders a diagnostics list with severity styling instead of a single tooltip", () => {
    render(
      <StructuredPlanOutput
        applyMode
        changes={[
          {
            address: "null_resource.fails",
            action: "create",
            actions: ["create"],
            after: {},
            status: "errored",
            diagnostics: [
              { severity: "warning", summary: "deprecated argument" },
              { severity: "error", summary: "local-exec provisioner error", detail: "exit code 1" },
            ],
          },
        ]}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: /null_resource\.fails/i }));

    expect(screen.getByText("deprecated argument", { selector: ".structured-plan-diagnostic--warning span" })).toBeInTheDocument();
    expect(
      screen.getByText("local-exec provisioner error", { selector: ".structured-plan-diagnostic--error span" })
    ).toBeInTheDocument();
    expect(screen.getByText("exit code 1")).toBeInTheDocument();
  });

  it("surfaces an errored badge and its diagnostic for a plan row with no diff, outside apply mode", () => {
    render(
      <StructuredPlanOutput
        changes={[
          {
            address: "module.this.module.inner.aws_secretsmanager_secret.test",
            status: "errored",
            diagnostics: [{ severity: "error", summary: "No valid credential sources found" }],
          },
        ]}
      />
    );

    expect(screen.getByText("unknown failed")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /aws_secretsmanager_secret\.test/i }));
    expect(
      screen.getByText("No valid credential sources found", { selector: ".structured-plan-diagnostic--error span" })
    ).toBeInTheDocument();
  });

  it("renders a job-level diagnostics panel for unaddressed diagnostics", () => {
    render(
      <StructuredPlanOutput
        applyMode
        changes={[{ address: "aws_instance.foo", action: "create", actions: ["create"], status: "applied" }]}
        jobDiagnostics={[{ severity: "warning", summary: "provider version is deprecated" }]}
      />
    );

    expect(
      screen.getByText("provider version is deprecated", { selector: ".structured-plan-jobDiagnostic--warning span" })
    ).toBeInTheDocument();
  });

  it("renders a collapsible provisioner-output section when present", () => {
    render(
      <StructuredPlanOutput
        applyMode
        changes={[
          {
            address: "null_resource.script",
            action: "create",
            actions: ["create"],
            status: "provisioning",
            currentProvisioner: "local-exec",
            provisionerOutput: ["hello from script"],
          },
        ]}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: /null_resource\.script/i }));
    fireEvent.click(screen.getByRole("button", { name: /provisioner output/i }));

    expect(screen.getByText("hello from script")).toBeInTheDocument();
  });

  it("labels the new refreshing/provisioning/ephemeral badge states correctly", () => {
    render(
      <StructuredPlanOutput
        applyMode
        changes={[
          { address: "aws_instance.a", action: "update", actions: ["update"], before: {}, after: {}, status: "refreshing" },
          { address: "null_resource.b", action: "create", actions: ["create"], status: "provisioning" },
          { address: "random_password.c", action: "create", actions: ["create"], status: "ephemeral-opening" },
        ]}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: /aws_instance\.a/i }));
    fireEvent.click(screen.getByRole("button", { name: /null_resource\.b/i }));
    fireEvent.click(screen.getByRole("button", { name: /random_password\.c/i }));

    expect(screen.getByText("refreshing", { selector: ".structured-plan-applyStatus" })).toBeInTheDocument();
    expect(screen.getByText("provisioning", { selector: ".structured-plan-applyStatus" })).toBeInTheDocument();
    expect(screen.getByText("opening", { selector: ".structured-plan-applyStatus" })).toBeInTheDocument();
  });

  it("shows a drift badge when a resource drifted outside Terraform/OpenTofu", () => {
    render(
      <StructuredPlanOutput
        changes={[
          {
            address: "aws_instance.drifted",
            action: "update",
            actions: ["update"],
            before: {},
            after: {},
            driftAction: "update",
          },
          { address: "aws_instance.stable", action: "update", actions: ["update"], before: {}, after: {} },
        ]}
      />
    );

    expect(screen.getByText("drift: update", { selector: ".structured-plan-drift" })).toBeInTheDocument();
  });

  it("renders an ephemeral resource row with its own action icon and status badge", () => {
    render(
      <StructuredPlanOutput
        applyMode
        changes={[
          {
            address: "ephemeral.random_password.session_secret",
            action: "ephemeral",
            status: "ephemeral-opening",
          },
        ]}
      />
    );

    expect(screen.getByRole("button", { name: /ephemeral\.random_password\.session_secret/i })).toBeInTheDocument();
    expect(screen.getByText("opening", { selector: ".structured-plan-applyStatus" })).toBeInTheDocument();
    expect(document.querySelector(".structured-plan-actionIcon--ephemeral")).toBeInTheDocument();
  });
});

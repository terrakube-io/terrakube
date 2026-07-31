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
    expect(screen.getByText('"sad-otter"')).toBeInTheDocument();
    expect(screen.getByText("random_password_result")).toBeInTheDocument();
    expect(screen.getByText("sensitive value")).toBeInTheDocument();
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
});

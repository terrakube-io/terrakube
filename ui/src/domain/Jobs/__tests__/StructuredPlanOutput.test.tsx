import { fireEvent, render, screen } from "@testing-library/react";
import { renderToStaticMarkup } from "react-dom/server";
import { StructuredPlanOutput } from "../StructuredPlanOutput";

describe("StructuredPlanOutput", () => {
  it("renders an empty state when there are no changes", () => {
    render(<StructuredPlanOutput changes={[]} />);

    expect(screen.getByText("No resource changes detected.")).toBeInTheDocument();
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
});

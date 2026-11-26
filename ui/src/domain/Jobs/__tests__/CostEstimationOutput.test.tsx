import { fireEvent, render, screen, within } from "@testing-library/react";
import { CostEstimationOutput } from "../CostEstimationOutput";
import { InfracostContext } from "@/domain/types";

const sampleInfracost: InfracostContext = {
  status: "SUCCESS",
  version: "0.2",
  currency: "USD",
  totalMonthlyCost: "162.50",
  totalHourlyCost: "0.22",
  pastTotalMonthlyCost: "120.00",
  pastTotalHourlyCost: "0.16",
  diffTotalMonthlyCost: "42.50",
  diffTotalHourlyCost: "0.06",
  shareUrl: "https://dashboard.infracost.io/share/example",
  summary: {
    totalDetectedResources: 4,
    totalSupportedResources: 2,
    totalUnsupportedResources: 1,
    totalNoPriceResources: 1,
    unsupportedResourceCounts: { aws_iam_role: 1 },
    noPriceResourceCounts: { aws_kms_key: 1 },
  },
  projects: [
    {
      name: "app-prod",
      pastBreakdown: {
        resources: [
          {
            name: "aws_instance.api",
            resourceType: "aws_instance",
            monthlyCost: "120.00",
            costComponents: [
              {
                name: "Instance usage (Linux/UNIX, on-demand, m5.large)",
                unit: "hours",
                monthlyQuantity: "730",
                price: "0.16438",
                monthlyCost: "120.00",
              },
            ],
          },
          {
            name: "aws_nat_gateway.legacy",
            resourceType: "aws_nat_gateway",
            monthlyCost: "32.85",
          },
        ],
      },
      breakdown: {
        resources: [
          {
            name: "aws_instance.api",
            resourceType: "aws_instance",
            monthlyCost: "140.00",
            costComponents: [
              {
                name: "Instance usage (Linux/UNIX, on-demand, m5.large)",
                unit: "hours",
                monthlyQuantity: "730",
                price: "0.16438",
                monthlyCost: "120.00",
              },
            ],
            subresources: [
              {
                name: "root_block_device",
                monthlyCost: "20.00",
                costComponents: [
                  {
                    name: "Storage (general purpose SSD, gp3)",
                    unit: "GB",
                    monthlyQuantity: "200",
                    price: "0.10",
                    monthlyCost: "20.00",
                  },
                ],
              },
            ],
          },
          {
            name: "aws_s3_bucket.assets",
            resourceType: "aws_s3_bucket",
            monthlyCost: "22.50",
            costComponents: [
              {
                name: "Standard storage",
                unit: "GB",
                monthlyQuantity: "978",
                price: "0.023",
                monthlyCost: "22.50",
              },
            ],
          },
          {
            name: "aws_kms_key.main",
            resourceType: "aws_kms_key",
            monthlyCost: null,
          },
        ],
      },
      diff: {
        resources: [
          {
            name: "aws_instance.api",
            monthlyCost: "20.00",
          },
          {
            name: "aws_s3_bucket.assets",
            monthlyCost: "22.50",
          },
        ],
      },
    },
  ],
};

describe("CostEstimationOutput", () => {
  it("renders KPIs, pre-deployment disclaimer, share link, and unpriced banner", () => {
    render(<CostEstimationOutput infracost={sampleInfracost} />);

    expect(screen.getByText(/Estimated pre-deployment monthly cost/i)).toBeInTheDocument();
    expect(screen.getByText("$162.50/mo")).toBeInTheDocument();
    expect(screen.getByText("+$42.50/mo (+35.4%)")).toBeInTheDocument();
    expect(screen.getByText("$120.00/mo")).toBeInTheDocument();
    expect(screen.getByText("2 / 4")).toBeInTheDocument();

    const shareLink = screen.getByRole("link", { name: /View in Infracost Cloud/i });
    expect(shareLink).toHaveAttribute("href", "https://dashboard.infracost.io/share/example");

    expect(screen.getByText(/2 unpriced or unsupported resource\(s\) omitted/i)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Show details" }));
    expect(screen.getByText(/aws_iam_role: 1 \(Unsupported\)/)).toBeInTheDocument();
    expect(screen.getByText(/aws_kms_key: 1 \(No price\)/)).toBeInTheDocument();
  });

  it("filters resources by pill and search query, and expands cost components", () => {
    render(<CostEstimationOutput infracost={sampleInfracost} />);

    const filterGroup = screen.getByRole("group", { name: "Filter resources by cost change" });

    // Filter to Created resources
    fireEvent.click(within(filterGroup).getByRole("button", { name: /Created/i }));
    expect(screen.getByText("aws_s3_bucket.assets")).toBeInTheDocument();
    expect(screen.queryByText("aws_instance.api")).not.toBeInTheDocument();

    // Filter back to All and search
    fireEvent.click(within(filterGroup).getByRole("button", { name: /^All/i }));
    fireEvent.change(screen.getByLabelText("Filter cost resources"), { target: { value: "aws_instance" } });
    expect(screen.getByText("aws_instance.api")).toBeInTheDocument();
    expect(screen.queryByText("aws_s3_bucket.assets")).not.toBeInTheDocument();

    // Expand resource cost components + subresources
    const rowButton = screen.getByRole("button", { name: /aws_instance\.api/i });
    fireEvent.click(rowButton);
    const listItem = rowButton.closest("li")!;
    expect(within(listItem).getByText("Instance usage (Linux/UNIX, on-demand, m5.large)")).toBeInTheDocument();
    expect(within(listItem).getByText("root_block_device")).toBeInTheDocument();
    expect(within(listItem).getByText("Storage (general purpose SSD, gp3)")).toBeInTheDocument();
  });

  it("omits unsafe or untrusted non-infracost shareUrl links", () => {
    for (const shareUrl of [
      "javascript:alert(1)",
      "http://dashboard.infracost.io/share/example",
      "https://evil.example.com/share/example",
      "https://user:pass@dashboard.infracost.io/share/example",
    ]) {
      const { unmount } = render(
        <CostEstimationOutput
          infracost={{
            ...sampleInfracost,
            shareUrl,
          }}
        />
      );
      expect(screen.queryByRole("link", { name: /View in Infracost Cloud/i })).not.toBeInTheDocument();
      unmount();
    }
  });

  it("displays an informational notice when the context payload is truncated and handles numeric totals", () => {
    render(
      <CostEstimationOutput
        infracost={{
          ...sampleInfracost,
          totalMonthlyCost: 162.5,
          pastTotalMonthlyCost: 120,
          diffTotalMonthlyCost: 42.5,
          truncated: true,
        }}
      />
    );

    expect(screen.getByTestId("cost-truncated-notice")).toBeInTheDocument();
    expect(screen.getByText("$162.50/mo")).toBeInTheDocument();
    expect(screen.getByText("+$42.50/mo (+35.4%)")).toBeInTheDocument();
  });

  it("renders graceful warning alert on ERROR status with redacted secrets", () => {
    render(
      <CostEstimationOutput
        infracost={{
          status: "ERROR",
          errorMessage: "Authentication failed using key ico-abcdefghijklmnop",
        }}
      />
    );

    expect(screen.getByText("Cost estimation unavailable")).toBeInTheDocument();
    expect(screen.getByText(/\[REDACTED\]/)).toBeInTheDocument();
    expect(screen.queryByText(/ico-abcdefghijklmnop/)).not.toBeInTheDocument();
  });
});

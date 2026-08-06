import { render } from "@testing-library/react";
import IacTypeLogo from "../IacTypeLogo";

describe("IacTypeLogo", () => {
  it("renders the Terraform icon for type 'terraform'", () => {
    const { container } = render(<IacTypeLogo type="terraform" />);
    expect(container.querySelector("svg")).toBeInTheDocument();
  });

  it("renders the OpenTofu logo for type 'tofu'", () => {
    const { container } = render(<IacTypeLogo type="tofu" />);
    const img = container.querySelector("img");
    expect(img).toBeInTheDocument();
    expect(img).toHaveAttribute("alt", "opentofu-logo");
  });

  it("renders nothing for an unknown type", () => {
    const { container } = render(<IacTypeLogo type="unknown" />);
    expect(container).toBeEmptyDOMElement();
  });
});

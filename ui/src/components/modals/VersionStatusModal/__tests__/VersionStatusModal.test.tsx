import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import VersionStatusModal, { VersionStatusAlert } from "../VersionStatusModal";
import { recommendedVersion, versionMenuItems, versionStatusSuffix } from "../versionStatus";

describe("VersionStatusModal", () => {
  it("saves a deprecation with a trimmed message", async () => {
    const onSave = jest.fn().mockResolvedValue(undefined);
    render(<VersionStatusModal open version="1.0.0" kind="module" status={{}} onCancel={jest.fn()} onSave={onSave} />);

    fireEvent.click(screen.getByLabelText("Deprecated"));
    fireEvent.change(await screen.findByLabelText("Message"), { target: { value: "  Use 2.x  " } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() =>
      expect(onSave).toHaveBeenCalledWith({ deprecated: true, removed: false, deprecationMessage: "Use 2.x" })
    );
  });

  it("turns the confirm button into a named destructive action when removing", async () => {
    const onSave = jest.fn().mockResolvedValue(undefined);
    render(
      <VersionStatusModal open version="1.0.0" kind="provider" status={{}} onCancel={jest.fn()} onSave={onSave} />
    );

    fireEvent.click(screen.getByLabelText("Removed"));
    const confirm = await screen.findByRole("button", { name: "Remove version 1.0.0" });
    expect(confirm).toHaveClass("ant-btn-dangerous");

    fireEvent.click(confirm);
    await waitFor(() =>
      expect(onSave).toHaveBeenCalledWith({ deprecated: false, removed: true, deprecationMessage: null })
    );
  });

  it("clears the message when a version is made active again", async () => {
    const onSave = jest.fn().mockResolvedValue(undefined);
    render(
      <VersionStatusModal
        open
        version="1.0.0"
        kind="module"
        status={{ removed: true, deprecationMessage: "Broken" }}
        onCancel={jest.fn()}
        onSave={onSave}
      />
    );

    fireEvent.click(screen.getByLabelText("Active"));
    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() =>
      expect(onSave).toHaveBeenCalledWith({ deprecated: false, removed: false, deprecationMessage: null })
    );
  });

  it("labels and explains deprecated and removed versions", () => {
    expect(versionStatusSuffix({})).toBe("");
    expect(versionStatusSuffix({ deprecated: true })).toBe(" (deprecated)");
    expect(versionStatusSuffix({ deprecated: true, removed: true })).toBe(" (removed)");

    const { rerender } = render(
      <VersionStatusAlert version="1.0.0" status={{ deprecated: true, deprecationMessage: "Removal on 2026-12-31" }} />
    );
    expect(screen.getByText("Version 1.0.0 is deprecated.")).toBeInTheDocument();
    expect(screen.getByText("Removal on 2026-12-31")).toBeInTheDocument();

    rerender(<VersionStatusAlert version="1.0.0" status={{ deprecated: true }} upgradeTo="2.0.0" />);
    expect(screen.getByText("Use version 2.0.0 instead.")).toBeInTheDocument();
  });

  it("recommends the newest active version and lists removed versions last", () => {
    const versions = [
      { version: "3.0.0", removed: true },
      { version: "2.0.0", deprecated: true },
      { version: "1.0.0" },
    ];
    expect(recommendedVersion(versions)?.version).toBe("1.0.0");
    expect(recommendedVersion([{ version: "2.0.0", deprecated: true }])?.version).toBe("2.0.0");
    expect(recommendedVersion([{ version: "3.0.0", removed: true }])).toBeUndefined();

    expect(versionMenuItems(versions, (v) => v.version)).toEqual([
      { key: "2.0.0", label: "2.0.0 (deprecated)" },
      { key: "1.0.0", label: "1.0.0" },
      { type: "divider" },
      { key: "3.0.0", label: "3.0.0 (removed)" },
    ]);
  });
});

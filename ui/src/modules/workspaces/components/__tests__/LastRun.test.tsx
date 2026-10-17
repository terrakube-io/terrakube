import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import LastRun from "../LastRun";
import { formatDateTimeWithZone } from "@/modules/utils/dates";

const twoHoursAgo = () => new Date(Date.now() - 2 * 60 * 60 * 1000).toISOString();

describe("LastRun", () => {
  it.each([undefined, null, "", "not a date"])("shows Never executed for %p", (value) => {
    render(<LastRun lastRun={value} />);
    expect(screen.getByText("Never executed")).toBeInTheDocument();
    expect(document.querySelector("time")).not.toBeInTheDocument();
  });

  it("shows the relative time as a time element that can take focus", () => {
    const lastRun = twoHoursAgo();
    render(<LastRun lastRun={lastRun} />);

    const time = screen.getByText("2 hours ago");
    expect(time.tagName).toBe("TIME");
    expect(time).toHaveAttribute("datetime", lastRun);
    expect(time).toHaveAttribute("tabindex", "0");
  });

  it("shows the exact time with its timezone on hover", async () => {
    const lastRun = twoHoursAgo();
    render(<LastRun lastRun={lastRun} />);

    await userEvent.hover(screen.getByText("2 hours ago"));
    expect(await screen.findByText(formatDateTimeWithZone(lastRun))).toBeInTheDocument();
  });

  it("shows the exact time when the text is reached with the keyboard", async () => {
    const lastRun = twoHoursAgo();
    render(<LastRun lastRun={lastRun} />);

    await userEvent.tab();
    expect(screen.getByText("2 hours ago")).toHaveFocus();
    expect(await screen.findByText(formatDateTimeWithZone(lastRun))).toBeInTheDocument();
  });
});

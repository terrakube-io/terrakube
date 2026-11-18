import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import ErrorBoundary from "../ErrorBoundary";

const Page = ({ crash }: { crash: boolean }) => {
  if (crash) throw new Error("boom");
  return <p>page content</p>;
};

const renderBoundary = (resetKey: string, crash: boolean) => (
  <MemoryRouter>
    <ErrorBoundary resetKey={resetKey}>
      <Page crash={crash} />
    </ErrorBoundary>
  </MemoryRouter>
);

beforeEach(() => {
  jest.spyOn(console, "error").mockImplementation(() => {});
});

afterEach(() => {
  jest.restoreAllMocks();
});

it("shows the error state when a child throws", () => {
  render(renderBoundary("/a", true));
  expect(screen.getByText("Something went wrong")).toBeInTheDocument();
  expect(screen.getByText("boom")).toBeInTheDocument();
});

it("keeps the error state while the reset key stays the same", () => {
  const { rerender } = render(renderBoundary("/a", true));
  rerender(renderBoundary("/a", false));
  expect(screen.getByText("Something went wrong")).toBeInTheDocument();
  expect(screen.queryByText("page content")).not.toBeInTheDocument();
});

it("renders the children again when the reset key changes", () => {
  const { rerender } = render(renderBoundary("/a", true));
  rerender(renderBoundary("/b", false));
  expect(screen.getByText("page content")).toBeInTheDocument();
  expect(screen.queryByText("Something went wrong")).not.toBeInTheDocument();
});

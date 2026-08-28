import { act, render } from "@testing-library/react";
import { createElement } from "react";
import { LogViewport } from "../LogViewport";

// jsdom does no layout; fake a 360px-tall scroll container.
const VIEWPORT_HEIGHT = 360;
const LINE_HEIGHT = 18;

function fakeLayout(scrollHeight: number) {
  Object.defineProperty(HTMLElement.prototype, "clientHeight", {
    configurable: true,
    get() {
      return VIEWPORT_HEIGHT;
    },
  });
  Object.defineProperty(HTMLElement.prototype, "scrollHeight", {
    configurable: true,
    get() {
      return scrollHeight;
    },
  });
}

const lines = (n: number) => Array.from({ length: n }, (_, i) => createElement("span", null, `line ${i}`));

afterEach(() => {
  jest.restoreAllMocks();
});

it("mounts only the visible slice of a large log", () => {
  fakeLayout(10_000 * LINE_HEIGHT);

  const { container } = render(
    <LogViewport lines={lines(10_000)} followTail={false} lineHeight={LINE_HEIGHT} overscan={10} />
  );

  const rendered = container.querySelectorAll("span").length;
  expect(rendered).toBeGreaterThan(0);
  expect(rendered).toBeLessThan(100); // ceil(360/18) + 2*10 = 40-ish, nowhere near 10000
});

it("re-windows on scroll", () => {
  fakeLayout(10_000 * LINE_HEIGHT);

  const { container } = render(
    <LogViewport lines={lines(10_000)} followTail={false} lineHeight={LINE_HEIGHT} overscan={5} />
  );

  const scroller = container.firstChild as HTMLDivElement;
  act(() => {
    scroller.scrollTop = 5000 * LINE_HEIGHT;
    scroller.dispatchEvent(new Event("scroll"));
  });

  const firstVisible = container.querySelector("span")?.textContent ?? "";
  const shown = Number(firstVisible.replace("line ", ""));
  expect(shown).toBeGreaterThan(4900);
});

it("reports follow released when the user scrolls up and re-engaged at the bottom", () => {
  fakeLayout(200 * LINE_HEIGHT);
  const onFollowChange = jest.fn();

  const { container } = render(
    <LogViewport
      lines={lines(200)}
      followTail
      onFollowChange={onFollowChange}
      lineHeight={LINE_HEIGHT}
    />
  );

  const scroller = container.firstChild as HTMLDivElement;

  act(() => {
    scroller.scrollTop = 100;
    scroller.dispatchEvent(new Event("scroll"));
  });
  expect(onFollowChange).toHaveBeenLastCalledWith(false);

  act(() => {
    scroller.scrollTop = 200 * LINE_HEIGHT;
    scroller.dispatchEvent(new Event("scroll"));
  });
  expect(onFollowChange).toHaveBeenLastCalledWith(true);
});

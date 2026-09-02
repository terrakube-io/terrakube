// jest-dom adds custom jest matchers for asserting on DOM nodes.
// allows you to do things like:
// expect(element).toHaveTextContent(/react/i)
// learn more: https://github.com/testing-library/jest-dom
import "@testing-library/jest-dom";
import { TextEncoder, TextDecoder } from "node:util";

// In the real app, window._env_ is injected by env-config.js at runtime. Several
// modules (apiWrapper.ts, axiosConfig.ts) read it at import time, so it must exist
// before those modules are ever required, even under a full jest.mock() auto-mock
// (which still evaluates the real module once to infer its shape).
// Values are arbitrary placeholders (using the IANA-reserved ".test" TLD) — no test
// makes real network calls to them, they just need to be well-formed, non-empty strings.
window._env_ = {
  REACT_APP_AUTHORITY: "https://terrakube-dex.test/dex",
  REACT_APP_CLIENT_ID: "terrakube-app",
  REACT_APP_REDIRECT_URI: "https://terrakube.test",
  REACT_APP_SCOPE: "email openid profile offline_access groups",
  REACT_APP_TERRAKUBE_API_URL: "https://terrakube-api.test/api/v1/",
  REACT_APP_TERRAKUBE_SEND_COOKIES: "false",
  REACT_APP_TERRAKUBE_VERSION: "test",
  REACT_APP_REGISTRY_URI: "https://terrakube-registry.test",
};

// jsdom's test environment doesn't provide these globals, but react-router-dom needs them.
global.TextEncoder = TextEncoder;
global.TextDecoder = TextDecoder as typeof global.TextDecoder;

// jsdom throws "not implemented" for getComputedStyle(elt, pseudoElt) with a pseudo-element
// argument (e.g. antd's Table scrollbar-size measurement calls it with '::-webkit-scrollbar').
// Drop the pseudo-element argument so jsdom's real implementation handles the call instead.
const originalGetComputedStyle = window.getComputedStyle.bind(window);
window.getComputedStyle = ((elt: Element) => originalGetComputedStyle(elt)) as typeof window.getComputedStyle;

// jsdom doesn't provide MessageChannel, but rc-select (antd Select's scheduling) and React 19's
// `scheduler` both need one. A prior shim wrapped node:worker_threads' MessageChannel with
// .unref()'d ports; those ports deadlock the scheduler's module init under jsdom on some hosts
// (WSL2), hanging every test that renders. This pure-JS version schedules delivery on a macrotask -
// enough for both consumers - and needs no unref because setTimeout(0) never keeps the loop alive.
class FakeMessagePort {
  onmessage: ((event: { data: unknown }) => void) | null = null;
  private peer!: FakeMessagePort;
  private listeners = new Set<(event: { data: unknown }) => void>();
  _link(peer: FakeMessagePort) {
    this.peer = peer;
  }
  private deliver(data: unknown) {
    const event = { data };
    this.onmessage?.(event);
    this.listeners.forEach((fn) => fn(event));
  }
  postMessage(data: unknown) {
    setTimeout(() => this.peer.deliver(data), 0);
  }
  start() {}
  close() {}
  addEventListener(type: string, fn: (event: { data: unknown }) => void) {
    if (type === "message") {
      this.listeners.add(fn);
    }
  }
  removeEventListener(type: string, fn: (event: { data: unknown }) => void) {
    if (type === "message") {
      this.listeners.delete(fn);
    }
  }
}
class FakeMessageChannel {
  port1 = new FakeMessagePort();
  port2 = new FakeMessagePort();
  constructor() {
    this.port1._link(this.port2);
    this.port2._link(this.port1);
  }
}
global.MessageChannel = FakeMessageChannel as unknown as typeof global.MessageChannel;

// jsdom doesn't implement ResizeObserver, but antd's Table/rc-resize-observer needs one.
global.ResizeObserver = class ResizeObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
};

// jsdom doesn't implement matchMedia, but antd's responsive breakpoint observer needs it.
Object.defineProperty(window, "matchMedia", {
  writable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  }),
});

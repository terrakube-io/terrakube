// jest.setup.ts replaces global.MessageChannel with an UnrefMessageChannel (node:worker_threads
// ports, .unref()'d). React 19's `scheduler` creates a MessageChannel at module init and drives its
// task loop through it; the unref'd worker_threads ports can deadlock that init under jsdom on some
// hosts, hanging any test file that (transitively) imports `react-dom/client` - which includes every
// @testing-library/react render.
//
// Import this module FIRST in such a test file, before @testing-library/react, so the delete runs
// before react-dom is loaded. jsdom's own MessageChannel (or scheduler's setTimeout fallback) then
// takes over.
//
// eslint-disable-next-line @typescript-eslint/no-explicit-any
delete (globalThis as any).MessageChannel;

export {};

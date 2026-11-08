# Terrakube UI: notes for agents and contributors

React 19 + TypeScript + antd 6, built with Vite (rolldown), tested with Jest + Testing Library.
Read this before changing anything under `ui/`.

## Toolchain

- Node is pinned to the current LTS major in `.node-version` (mise, fnm, nvm and `actions/setup-node` read it).
  `package.json` `engines` enforces the same major, and the Dockerfile builder uses the same image.
- Node 25+ does not bundle Corepack. Install it once per Node version, then use the Yarn version that
  `packageManager` pins:

  ```bash
  npm install -g corepack@0.36.0 && corepack enable
  yarn install --immutable
  ```

- Bump the pin each October, when the next even Node major becomes Active LTS: change `.node-version`,
  `engines.node` and the Dockerfile `FROM` (with its digest) in one PR.
- `.yarnrc.yml` refuses package versions published less than three days ago (`npmMinimalAgeGate`).
  A brand-new release you need will install once it is three days old.

## Commands

| Command | What it does |
|---|---|
| `yarn check` | lint, format check, typecheck and unit tests: run it before every commit |
| `yarn lint:check` / `yarn lint` | ESLint on `src` (errors block CI, warnings don't) |
| `yarn format:check` / `yarn format` | Prettier on `src` |
| `yarn typecheck` | `tsc` for app code and for tests (two projects) |
| `yarn test` | Jest, about a minute |
| `yarn build` | production build into `build/`; it does **not** typecheck |
| `yarn start` | dev server on :3000 (needs `.env`, see README) |

## Layout

- New code goes in `src/modules/<feature>/` (services, pages, feature components) and
  `src/components/<kind>/` (shared UI: display, feedback, forms, layout, modals, navigation, settings).
- `src/domain/` is the older layout. Fix things there, but don't add new features to it.
- Tests live next to the code in `__tests__/` folders. Use `@/` imports (`@/modules/...`), not long `../../`.
- Routes are declared in `src/domain/Home/App.tsx`; every page there is `lazy()`-loaded.

## Talking to the API

| Client | Use it for |
|---|---|
| `apiGet` / `apiPost` / `apiPut` / `apiDelete` from `@/modules/api/apiWrapper` | default for new code: adds the token, never throws, returns `ApiResponse<T>` (`isError`, `data`, `error`), signs out on 401 |
| `axiosInstance` (default export of `@/config/axiosConfig`) | JSON:API calls whose failure should show the app-wide "API not responding" screen |
| `axiosAuxiliary` | optional panels (logs, context, polls): same auth, but 404/5xx/timeouts stay local to the component |
| `axiosGraphQL` | Elide GraphQL (`/graphql/api/v1`) |
| `axiosRegistry` | the registry proxy and other paths at the API origin without `/api/v1` |
| `axiosClient` | unauthenticated calls only: it has no interceptors |

Runtime configuration comes from `window._env_` (written to `env-config.js` at container start by
`env.sh`), never from `import.meta.env`. `jest.setup.ts` defines it for tests.

## State

React state and contexts (`src/context/`, `src/modules/*/…Context.tsx`). A few values cross pages through
`sessionStorage` under the keys in `src/config/actionTypes.ts`. There is no Redux.

## Dates

Use `dayjs` (antd already ships it) and the helpers in `src/modules/utils/dates.ts`. Absolute dates go
through `Intl`, so they follow the browser locale. `luxon` stays installed only because `ActionLoader` hands
`DateTime` to user-defined actions; don't import it anywhere else, and don't add another date library.

## Keeping the bundle small

The first page should only load the shell (layout, sidebar, login). Measure before and after a change
that touches imports:

```bash
yarn build >/dev/null && cd build && tot=0
for f in $(grep -o 'assets/[^"]*\.\(js\|css\)' index.html | sort -u); do
  tot=$((tot + $(gzip -c "$f" | wc -c))); done; echo "entry closure: $tot bytes gzip"
```

`ANALYZE=1 yarn build` writes a treemap to `build/bundle-stats.html`.

Rules:

- Never import these statically from the shell or from a module the shell imports: `monaco-editor` /
  `@monaco-editor/react`, `@scalar/*`, `react-markdown` and its plugins, `reactflow`, `acorn`, `sucrase`.
  Load them inside a lazy route or with `await import()`.
- Use the code editor only through `@/components/forms/CodeEditor`; import only types from
  `@monaco-editor/react`.
- `src/ActionLoader.tsx` compiles user-defined actions at runtime and gives them the antd namespace.
  Don't narrow what it exposes: existing actions rely on it.

## Pitfalls

- `console.log/info/debug/warn` are compiled out of production builds; use `console.error` for real errors.
- jsdom lacks a few browser APIs; the shims are in `jest.setup.ts`. Mock modules by the same path the
  component imports (`jest.mock("@/config/axiosConfig", …)`).
- Some modules read `window._env_` at import time, so a test must not reset it before importing them.
- Don't silence a check to get green: no `eslint-disable`, `@ts-ignore` or `test.skip` without a reason
  in the PR.
- Public text (code, commits, PRs) must not name competing commercial products.

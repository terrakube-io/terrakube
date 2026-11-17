---
name: terrakube-ui
description: Guidelines, commands, and conventions for building, testing, linting, and formatting the Terrakube UI application (`ui`).
---

# Terrakube UI Guide (`ui`)

The Terrakube UI is a single-page application built with React, TypeScript, Vite, Ant Design, and Jest, located in `ui`.

---

## 1. Directory & Stack Overview

- **Location**: `ui`
- **Package Manager**: `yarn` (`yarn@4.15.0`, Node 24)
- **Stack**: React 19, TypeScript, Ant Design 6, `react-router-dom` 7
- **Build Tool**: Vite (`vite build`)
- **Testing**: Jest + `@testing-library/react`
- **Linting & Formatting**: ESLint (flat config `eslint.config.js`) + Prettier

Key module paths:
- `src/modules/`: Domain-driven module packages (`api`, `apiDocs`, `organizations`, `permissions`, `projects`, `token`, `user`, `utils`, `workspaces`)
- `src/domain/`: Core and legacy feature views (`Home`, `Jobs`, `Login`, `Modules`, `Notifications`, `Organizations`, `Providers`, `Settings`, `Workspaces`) — `Home/App.tsx` configures `createBrowserRouter` with lazy-loaded `@/modules/*` pages and workspace settings sub-routes
- `src/components/`: Shared UI components (`display`, `feedback`, `forms`, `layout`, `modals`, `navigation`, `settings`)
- `src/hooks/`: Custom hooks (including SSE/streaming hooks like `useEventStream` and `useStructuredOutputStream`)
- `src/config/`, `src/context/`, `src/types/`: App configuration, auth/OIDC context, and shared TypeScript types

---

## 2. Linting and Code Formatting

CI checks PRs using modular lint and format commands. ESLint includes `prettier/prettier` rules, so formatting violations cause ESLint check failures.

### Modular Commands (Targeting `src/modules`)
- **Check Linting**:
  ```bash
  yarn lint:modules:check
  ```
- **Auto-Fix Linting & Formatting**:
  ```bash
  yarn lint:modules
  ```
- **Check Prettier Formatting**:
  ```bash
  yarn format:modules:check
  ```
- **Auto-Fix Prettier Formatting**:
  ```bash
  yarn format:modules
  ```

### Global Commands (Targeting all of `src`)
- **Check Linting**: `yarn lint:check`
- **Auto-Fix Linting**: `yarn lint`
- **Check Prettier**: `yarn format:check`
- **Auto-Fix Prettier**: `yarn format`

### Common Lint Gotchas
- **Unused imports & variables**: Any unused import (e.g. `fireEvent` from `@testing-library/react`) triggers `@typescript-eslint/no-unused-vars` and blocks CI.
- **Prettier line wrapping & trailing commas**: Prettier formatting is enforced directly inside ESLint; running `yarn lint:modules` automatically formats code to match configured rules.

---

## 3. Running Tests

The test suite runs with Jest. By default in local environments, `yarn test` runs in interactive watch mode.

- **Automated / CI Test Run (Non-Interactive)**:
  ```bash
  yarn test --watchAll=false
  ```
  *(Alternatively: `CI=true yarn test`)*
- **Run Specific Test File**:
  ```bash
  yarn test src/modules/workspaces/components/__tests__/WorkspaceCard.test.tsx --watchAll=false
  ```
- **Update Snapshots**:
  ```bash
  yarn test -u --watchAll=false
  ```

---

## 4. Building the UI

- **Production Build**:
  ```bash
  yarn build
  ```
  Generates production assets in `ui/build`.
- **Bundle Analysis**:
  ```bash
  yarn build:analyze
  ```
- **Local Dev Server**:
  ```bash
  yarn start
  ```
- **Preview Production Build**:
  ```bash
  yarn serve
  ```

---

## 5. Pre-Commit / Pre-PR Checklist

Before pushing frontend changes to Git:
1. `yarn lint:modules` (or `yarn lint` for full project)
2. `yarn lint:modules:check` (verify 0 errors)
3. `yarn test --watchAll=false` (verify all test suites pass)
4. `yarn build` (verify bundle builds cleanly without TypeScript or bundler errors)

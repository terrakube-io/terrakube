import { loader, type OnMount } from "@monaco-editor/react";
// Deliberately not `import * as monaco from "monaco-editor"`: the root entry registers ~80 language
// definitions, the CSS/HTML/JSON/TS language services, an LSP client and every editor feature.
// Terrakube uses the editor in exactly three places: read-only JSON on the workspace States page,
// YAML for templates, and JavaScript for actions, and each one only reads and writes text. So the
// editor is assembled from the API, a curated feature set, and those three languages. The TypeScript
// language service is left out on purpose: its worker alone is ~1.4 MB gzipped and only adds
// diagnostics that misfire on the JSX-style action code anyway. Syntax highlighting stays.
import * as monaco from "monaco-editor/editor/editor.api.js";
import "monaco-editor/features/bracketMatching/register.js";
import "monaco-editor/features/clipboard/register.js";
import "monaco-editor/features/codicon/register.js";
import "monaco-editor/features/comment/register.js";
import "monaco-editor/features/contextmenu/register.js";
import "monaco-editor/features/cursorUndo/register.js";
import "monaco-editor/features/find/register.js";
import "monaco-editor/features/folding/register.js";
import "monaco-editor/features/gotoLine/register.js";
import "monaco-editor/features/indentation/register.js";
import "monaco-editor/features/linesOperations/register.js";
import "monaco-editor/features/multicursor/register.js";
import "monaco-editor/features/readOnlyMessage/register.js";
import "monaco-editor/features/suggest/register.js";
import "monaco-editor/features/tokenization/register.js";
import "monaco-editor/features/unicodeHighlighter/register.js";
import "monaco-editor/features/wordHighlighter/register.js";
import "monaco-editor/features/wordOperations/register.js";
import "monaco-editor/languages/definitions/yaml/register.js";
import "monaco-editor/languages/definitions/javascript/register.js";
import { jsonDefaults } from "monaco-editor/languages/features/json/register.js";
import editorWorker from "monaco-editor/editor/editor.worker.js?worker";
import jsonWorker from "monaco-editor/language/json/json.worker.js?worker";
import { ThemeMode } from "./themeConfig";

self.MonacoEnvironment = {
  getWorker(_workerId: string, label: string) {
    if (label === "json") return new jsonWorker();
    return new editorWorker();
  },
};

// JSON is only ever shown read-only (workspace state), so don't have the worker parse and
// validate a multi-megabyte state file just to draw markers nobody can act on.
jsonDefaults.setDiagnosticsOptions({ validate: false });

loader.config({ monaco });

type IStandaloneCodeEditor = Parameters<OnMount>[0];

export const getMonacoTheme = (themeMode: ThemeMode): string => {
  return themeMode === "dark" ? "vs-dark" : "vs-light";
};

export const monacoOptions: Parameters<IStandaloneCodeEditor["updateOptions"]>[0] = {
  minimap: {
    enabled: false,
  },
  scrollBeyondLastLine: false,
  fontSize: 14,
  lineNumbers: "on",
  roundedSelection: false,
  scrollbar: {
    vertical: "visible",
    horizontal: "visible",
    useShadows: false,
    verticalScrollbarSize: 10,
    horizontalScrollbarSize: 10,
  },
};

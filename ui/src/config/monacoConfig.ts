import { loader, type OnMount } from "@monaco-editor/react";
import * as monaco from "monaco-editor";
import editorWorker from "monaco-editor/editor/editor.worker.js?worker";
import jsonWorker from "monaco-editor/language/json/json.worker.js?worker";
import tsWorker from "monaco-editor/language/typescript/ts.worker.js?worker";
import { ThemeMode } from "./themeConfig";

self.MonacoEnvironment = {
  getWorker(_workerId: string, label: string) {
    if (label === "json") return new jsonWorker();
    if (label === "javascript" || label === "typescript") return new tsWorker();
    return new editorWorker();
  },
};

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

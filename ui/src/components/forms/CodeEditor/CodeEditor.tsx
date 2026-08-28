import { Editor } from "@monaco-editor/react";
import type { EditorProps } from "@monaco-editor/react";
import { getMonacoTheme, monacoOptions } from "@/config/monacoConfig";
import { useTheme } from "@/context/ThemeContext";
import "./CodeEditor.css";

type Props = Omit<EditorProps, "theme">;

export default function CodeEditor({ options, height = "45vh", ...editorProps }: Props) {
  const { themeMode } = useTheme();

  return (
    <div className="code-editor">
      <Editor
        height={height}
        theme={getMonacoTheme(themeMode)}
        options={{ ...monacoOptions, ...options }}
        {...editorProps}
      />
    </div>
  );
}

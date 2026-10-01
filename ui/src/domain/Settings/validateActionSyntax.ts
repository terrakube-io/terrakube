import { Parser } from "acorn";
import jsx from "acorn-jsx";

const parser = Parser.extend(jsx());

export function validateActionSyntax(code: string): string | null {
  try {
    // ActionLoader consumes a component expression, including anonymous functions and JSX.
    const expression = code.trim().replace(/;+\s*$/, "");
    parser.parse(`(${expression}\n)`, { ecmaVersion: "latest", sourceType: "script" });
    return null;
  } catch (error) {
    return error instanceof SyntaxError ? error.message : "Invalid action syntax";
  }
}

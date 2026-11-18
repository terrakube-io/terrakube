// The parser is only needed when an action is saved, so it is loaded on demand.
export async function validateActionSyntax(code: string): Promise<string | null> {
  const [{ Parser }, { default: jsx }] = await Promise.all([import("acorn"), import("acorn-jsx")]);
  const parser = Parser.extend(jsx());
  try {
    // ActionLoader consumes a component expression, including anonymous functions and JSX.
    const expression = code.trim().replace(/;+\s*$/, "");
    parser.parse(`(${expression}\n)`, { ecmaVersion: "latest", sourceType: "script" });
    return null;
  } catch (error) {
    return error instanceof SyntaxError ? error.message : "Invalid action syntax";
  }
}

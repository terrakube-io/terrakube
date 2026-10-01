import { validateActionSyntax } from "../validateActionSyntax";

it.each([
  "function(context) { return <Button>{context.name}</Button>; }",
  "(context) => <>{context?.name ?? 'Run'}</>;",
  "() => { return /[()]/.test('text'); } // trailing comment",
])("accepts component expressions without executing them: %s", (code) => {
  expect(validateActionSyntax(code)).toBeNull();
});

it.each(["", "() => { return (; }", "() => <div></span>", "() => { const value; }", "() => {}; broken("])(
  "rejects invalid action syntax: %s",
  (code) => {
    expect(validateActionSyntax(code)).not.toBeNull();
  }
);

it("never executes the submitted code", () => {
  const run = jest.fn();
  Object.assign(globalThis, { actionValidationProbe: run });
  expect(validateActionSyntax("(() => { globalThis.actionValidationProbe(); return () => null; })()")).toBeNull();
  expect(run).not.toHaveBeenCalled();
  Reflect.deleteProperty(globalThis, "actionValidationProbe");
});

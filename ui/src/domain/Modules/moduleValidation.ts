export const MODULE_SYSTEM_PATTERN = /^[A-Za-z0-9]{1,64}$/;

export function isValidModuleSystem(value: string): boolean {
  return MODULE_SYSTEM_PATTERN.test(value);
}

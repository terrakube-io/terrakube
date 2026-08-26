import type { Rule } from "antd/es/form";

export const organizationNameRules: Rule[] = [
  { required: true, message: "This field is required!" },
  { pattern: /^[a-zA-Z0-9]*$/, message: "Only letters and numbers are allowed!" },
];

import { Radio, RadioGroupProps } from "antd";
import clsx from "classnames";
import "./RadioChoices.css";

type Choice = {
  value: string;
  label: React.ReactNode;
  help?: React.ReactNode;
};

type Props = Omit<RadioGroupProps, "options" | "children"> & {
  options: Choice[];
};

// Stacked radios with a help line each, for 2-3 options that need an explanation.
export default function RadioChoices({ options, className, ...groupProps }: Props) {
  return (
    <Radio.Group {...groupProps} className={clsx("radio-choices", className)}>
      {options.map((option) => (
        <Radio key={option.value} value={option.value}>
          {option.label}
          {option.help && <span className="radio-choices-help">{option.help}</span>}
        </Radio>
      ))}
    </Radio.Group>
  );
}

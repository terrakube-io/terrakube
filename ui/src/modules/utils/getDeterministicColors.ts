import isLightColor from "./isLightColor";
import stringToDeterministicColor from "./stringToDeterministicColor";

export default function (seed: string) {
  const color = stringToDeterministicColor(seed);

  return {
    color: isLightColor(color) ? "#000000" : "#ffffff",
    background: color,
  };
}

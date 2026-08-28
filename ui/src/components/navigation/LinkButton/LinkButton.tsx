import { Button } from "antd";
import type { ButtonProps } from "antd";
import { useHref, useNavigate } from "react-router-dom";

type Props = Omit<ButtonProps, "href"> & {
  to: string;
};

export default function LinkButton({ to, onClick, ...buttonProps }: Props) {
  const href = useHref(to);
  const navigate = useNavigate();

  return (
    <Button
      {...buttonProps}
      href={buttonProps.disabled ? undefined : href}
      onClick={(event) => {
        onClick?.(event as React.MouseEvent<HTMLButtonElement>);
        if (event.defaultPrevented) {
          return;
        }
        const mouse = event as React.MouseEvent;
        if (mouse.metaKey || mouse.ctrlKey || mouse.shiftKey || mouse.altKey || mouse.button !== 0) {
          return;
        }
        event.preventDefault();
        navigate(to);
      }}
    />
  );
}

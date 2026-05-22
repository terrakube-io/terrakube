import { IconContext } from "react-icons";
import { SiTerraform } from "react-icons/si";
import { withBasePath } from "@/config/basePath";

type Props = {
  type: string;
};
export default function IacTypeLogo({ type }: Props) {
  switch (type) {
    case "terraform":
      return (
        <IconContext.Provider value={{ size: "18px" }}>
          <SiTerraform />
        </IconContext.Provider>
      );
    case "opentofu":
      return <img width="18px" alt="opentofu-logo" src={withBasePath("/providers/opentofu.png")} />;

    default:
      return null;
  }
}

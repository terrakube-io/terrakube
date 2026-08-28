import { useLocation } from "react-router-dom";
import { Tokens } from "./components/PatSection/PatSection";
import { ThemeSection } from "./components/ThemeSection/ThemeSection";
import PageWrapper from "@/components/PageWrapper/PageWrapper";

export const UserSettingsPage = () => {
  const location = useLocation();
  const isTheme = location.pathname.includes("/settings/theme");

  return (
    <PageWrapper
      title="Account Settings"
      breadcrumbs={[{ label: "Account Settings" }, { label: isTheme ? "Theme" : "Tokens" }]}
      width="reading"
    >
      {isTheme ? <ThemeSection /> : <Tokens />}
    </PageWrapper>
  );
};

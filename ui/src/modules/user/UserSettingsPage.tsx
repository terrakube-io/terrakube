import { useLocation } from "react-router-dom";
import { Tokens } from "./components/PatSection/PatSection";
import { ThemeSection } from "./components/ThemeSection/ThemeSection";
import PageWrapper from "@/components/layout/PageWrapper/PageWrapper";

export const UserSettingsPage = () => {
  const location = useLocation();
  const isTheme = location.pathname.includes("/settings/theme");

  return (
    <PageWrapper
      title="Account settings"
      showTitle={false}
      breadcrumbs={[{ label: "Account settings" }, { label: isTheme ? "Theme" : "Tokens" }]}
    >
      {isTheme ? <ThemeSection /> : <Tokens />}
    </PageWrapper>
  );
};

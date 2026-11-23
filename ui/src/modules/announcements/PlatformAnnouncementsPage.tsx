import { ErrorState } from "@/components/feedback/ErrorState";
import { Loading } from "@/components/feedback/Loading";
import PageWrapper from "@/components/layout/PageWrapper/PageWrapper";
import { useSuperUser } from "@/modules/permissions/useSuperUser";
import { AnnouncementsManager } from "./components/AnnouncementsManager";

export function PlatformAnnouncementsPage() {
  const { isSuperUser, loading } = useSuperUser();

  return (
    <PageWrapper
      title="Platform announcements"
      showTitle={false}
      breadcrumbs={[{ label: "Organizations", path: "/organizations" }, { label: "Platform announcements" }]}
    >
      {loading ? (
        <Loading loading description="Checking platform permissions..." />
      ) : !isSuperUser ? (
        <ErrorState status={403} message="Only platform superusers can manage platform announcements." />
      ) : (
        <AnnouncementsManager mode="platform" canManage={true} isSuperUser={true} />
      )}
    </PageWrapper>
  );
}

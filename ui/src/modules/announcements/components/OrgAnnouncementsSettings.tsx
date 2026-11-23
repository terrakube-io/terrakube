import { Tabs } from "antd";
import { useParams } from "react-router-dom";
import SettingsPageHeader from "@/components/settings/SettingsPageHeader/SettingsPageHeader";
import { AnnouncementsManager } from "./AnnouncementsManager";

export function OrgAnnouncementsSettings({
  manageAnnouncements,
  isSuperUser,
}: {
  manageAnnouncements: boolean;
  isSuperUser: boolean;
}) {
  const { orgid } = useParams<{ orgid: string }>();

  if (!isSuperUser) {
    return (
      <AnnouncementsManager
        mode="organization"
        organizationId={orgid}
        canManage={manageAnnouncements}
        isSuperUser={false}
      />
    );
  }

  return (
    <div className="setting">
      <SettingsPageHeader
        title="Announcements"
        description="Manage organization announcements and global platform-wide maintenance banners."
      />
      <Tabs
        defaultActiveKey="org"
        items={[
          {
            key: "org",
            label: "Organization Announcements",
            children: (
              <AnnouncementsManager
                mode="organization"
                organizationId={orgid}
                canManage={manageAnnouncements}
                isSuperUser={true}
                hideHeader={true}
              />
            ),
          },
          {
            key: "platform",
            label: "Platform Announcements",
            children: (
              <AnnouncementsManager
                mode="platform"
                organizationId={orgid}
                canManage={true}
                isSuperUser={true}
                hideHeader={true}
              />
            ),
          },
        ]}
      />
    </div>
  );
}

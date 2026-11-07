import { useEffect, useState, useCallback, useMemo } from "react";
import { Announcement } from "./announcementTypes";
import { fetchAnnouncements, filterAndSortAnnouncements } from "./announcementService";
import {
  isAnnouncementDismissed,
  dismissAnnouncement,
  clearDismissal,
  isAnnouncementViewed,
  markAnnouncementsViewed,
} from "./announcementStorage";

export function useAnnouncements(currentOrgId?: string | null) {
  const [allAnnouncements, setAllAnnouncements] = useState<Announcement[]>([]);
  const [loading, setLoading] = useState(true);
  const [dismissTick, setDismissTick] = useState(0);
  const [viewedTick, setViewedTick] = useState(0);

  const loadAnnouncements = useCallback(async () => {
    try {
      setLoading(true);
      const data = await fetchAnnouncements();
      setAllAnnouncements(data);
    } catch {
      // In case of error (e.g. backend temporarily unreachable), keep empty to prevent blocking UI
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadAnnouncements();
    // Re-check periodically every 60 seconds for scheduled window transitions
    const interval = setInterval(loadAnnouncements, 60000);
    return () => clearInterval(interval);
  }, [loadAnnouncements]);

  const orgFiltered = useMemo(() => {
    return filterAndSortAnnouncements(allAnnouncements, currentOrgId ?? undefined);
  }, [allAnnouncements, currentOrgId]);

  const activeAnnouncements = useMemo(() => {
    return orgFiltered.filter((a) => !isAnnouncementDismissed(a));
  }, [orgFiltered, dismissTick]);

  // Top banners are reserved for Critical and Warning ambient broadcast alerts
  const bannerAnnouncements = useMemo(() => {
    return activeAnnouncements.filter((a) => a.severity === "critical" || a.severity === "warning");
  }, [activeAnnouncements]);

  const dismissedAnnouncements = useMemo(() => {
    return orgFiltered.filter((a) => isAnnouncementDismissed(a));
  }, [orgFiltered, dismissTick]);

  // Unread count: notices not yet viewed in drawer and NOT already pinned on screen
  const unreadCount = useMemo(() => {
    const bannerIds = new Set(bannerAnnouncements.map((b) => b.id));
    return activeAnnouncements.filter((a) => !isAnnouncementViewed(a) && !bannerIds.has(a.id)).length;
  }, [activeAnnouncements, bannerAnnouncements, viewedTick]);

  const handleDismiss = useCallback((a: Announcement) => {
    dismissAnnouncement(a);
    setDismissTick((t) => t + 1);
  }, []);

  const handleRestore = useCallback((id: string) => {
    clearDismissal(id);
    setDismissTick((t) => t + 1);
  }, []);

  const handleMarkViewed = useCallback(() => {
    markAnnouncementsViewed(activeAnnouncements);
    setViewedTick((t) => t + 1);
  }, [activeAnnouncements]);

  return {
    announcements: activeAnnouncements,
    bannerAnnouncements,
    dismissedAnnouncements,
    allAnnouncements,
    unreadCount,
    loading,
    refresh: loadAnnouncements,
    dismissAnnouncement: handleDismiss,
    restoreAnnouncement: handleRestore,
    markViewed: handleMarkViewed,
  };
}

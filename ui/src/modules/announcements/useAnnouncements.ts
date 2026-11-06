import { useEffect, useState, useCallback, useMemo } from "react";
import { Announcement } from "./announcementTypes";
import { fetchAnnouncements, filterAndSortAnnouncements } from "./announcementService";
import { isAnnouncementDismissed, dismissAnnouncement, clearDismissal } from "./announcementStorage";

export function useAnnouncements(currentOrgId?: string | null) {
  const [allAnnouncements, setAllAnnouncements] = useState<Announcement[]>([]);
  const [loading, setLoading] = useState(true);
  const [dismissTick, setDismissTick] = useState(0);

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

  const dismissedAnnouncements = useMemo(() => {
    return orgFiltered.filter((a) => isAnnouncementDismissed(a));
  }, [orgFiltered, dismissTick]);

  const handleDismiss = useCallback((a: Announcement) => {
    dismissAnnouncement(a);
    setDismissTick((t) => t + 1);
  }, []);

  const handleRestore = useCallback((id: string) => {
    clearDismissal(id);
    setDismissTick((t) => t + 1);
  }, []);

  return {
    announcements: activeAnnouncements,
    dismissedAnnouncements,
    allAnnouncements,
    loading,
    refresh: loadAnnouncements,
    dismissAnnouncement: handleDismiss,
    restoreAnnouncement: handleRestore,
  };
}

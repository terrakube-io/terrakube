import { useEffect, useState, useCallback } from "react";
import { Announcement } from "./announcementTypes";
import { fetchAnnouncements, filterAndSortAnnouncements } from "./announcementService";

export function useAnnouncements(currentOrgId?: string) {
  const [allAnnouncements, setAllAnnouncements] = useState<Announcement[]>([]);
  const [loading, setLoading] = useState(true);

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

  const activeAnnouncements = filterAndSortAnnouncements(allAnnouncements, currentOrgId);

  return {
    announcements: activeAnnouncements,
    allAnnouncements,
    loading,
    refresh: loadAnnouncements,
  };
}

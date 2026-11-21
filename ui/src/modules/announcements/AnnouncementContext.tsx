import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { AnnouncementContext } from "./AnnouncementStore";
import { fetchAnnouncementsForBanner } from "./announcementService";
import { getNextScheduleBoundaryMs, isAnnouncementCurrentlyActive, sortAnnouncements } from "./announcementUtils";
import {
  dismissAnnouncement,
  getDismissedMap,
  isAnnouncementDismissed,
  type DismissedAnnouncementMap,
} from "./dismissalStorage";
import type { AnnouncementItem } from "./types";

const POLL_INTERVAL_MS = 60_000;

export function AnnouncementProvider({
  orgId,
  routeKey,
  children,
}: {
  orgId: string | null;
  routeKey: string;
  children: ReactNode;
}) {
  const [announcements, setAnnouncements] = useState<AnnouncementItem[]>([]);
  const [clockOffsetMs, setClockOffsetMs] = useState<number>(0);
  const [tick, setTick] = useState<number>(0);
  const [loading, setLoading] = useState<boolean>(true);
  const [dismissedMap, setDismissedMap] = useState<DismissedAnnouncementMap>(() => getDismissedMap());
  const requestSeq = useRef(0);

  const refreshAnnouncements = useCallback(async () => {
    const currentSeq = ++requestSeq.current;
    try {
      const { announcements: fetched, serverTimeMs } = await fetchAnnouncementsForBanner(orgId);
      if (currentSeq !== requestSeq.current) {
        return;
      }
      setAnnouncements(fetched);
      setClockOffsetMs(serverTimeMs - Date.now());
      setTick((t) => t + 1);
    } catch {
      // Auxiliary request fails silently so banner outages never block workspace views
    } finally {
      if (currentSeq === requestSeq.current) {
        setLoading(false);
      }
    }
  }, [orgId]);

  useEffect(() => {
    void refreshAnnouncements();
  }, [refreshAnnouncements, routeKey]);

  useEffect(() => {
    const intervalId = window.setInterval(() => {
      void refreshAnnouncements();
    }, POLL_INTERVAL_MS);
    return () => window.clearInterval(intervalId);
  }, [refreshAnnouncements]);

  const serverNowMs = Date.now() + clockOffsetMs;

  useEffect(() => {
    const nowMs = Date.now() + clockOffsetMs;
    const nextBoundaryMs = getNextScheduleBoundaryMs(announcements, nowMs);
    if (nextBoundaryMs === null) {
      return;
    }
    const timeoutId = window.setTimeout(() => {
      setTick((t) => t + 1);
      void refreshAnnouncements();
    }, nextBoundaryMs + 250);
    return () => window.clearTimeout(timeoutId);
  }, [announcements, clockOffsetMs, refreshAnnouncements, tick]);

  const visibleAnnouncements = useMemo(() => {
    const currentServerNow = Date.now() + clockOffsetMs;
    const active = announcements.filter(
      (item) => isAnnouncementCurrentlyActive(item, currentServerNow) && !isAnnouncementDismissed(item, dismissedMap)
    );
    return sortAnnouncements(active);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [announcements, clockOffsetMs, dismissedMap, tick]);

  const dismiss = useCallback((item: AnnouncementItem) => {
    if (!item.dismissible) {
      return;
    }
    const nextMap = dismissAnnouncement(item);
    setDismissedMap(nextMap);
  }, []);

  const value = useMemo(
    () => ({
      visibleAnnouncements,
      serverNowMs,
      loading,
      dismiss,
      refreshAnnouncements,
    }),
    [visibleAnnouncements, serverNowMs, loading, dismiss, refreshAnnouncements]
  );

  return <AnnouncementContext.Provider value={value}>{children}</AnnouncementContext.Provider>;
}

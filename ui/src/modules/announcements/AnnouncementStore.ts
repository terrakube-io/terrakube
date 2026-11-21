import { createContext } from "react";
import type { AnnouncementItem } from "./types";

export interface AnnouncementContextValue {
  visibleAnnouncements: AnnouncementItem[];
  serverNowMs: number;
  loading: boolean;
  dismiss: (announcement: AnnouncementItem) => void;
  refreshAnnouncements: () => Promise<void>;
}

export const AnnouncementContext = createContext<AnnouncementContextValue | undefined>(undefined);

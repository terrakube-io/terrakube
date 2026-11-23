import { useContext } from "react";
import { AnnouncementContext } from "./AnnouncementStore";

export function useAnnouncements() {
  const context = useContext(AnnouncementContext);
  if (!context) {
    throw new Error("useAnnouncements must be used within an AnnouncementProvider");
  }
  return context;
}

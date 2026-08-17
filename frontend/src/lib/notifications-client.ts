import { apiFetch, apiPost } from "@/lib/api";

export interface AppNotification {
  id: string;
  type:
    | "MESSAGE"
    | "MESSAGE_REQUEST"
    | "SAVED_SEARCH_ALERT"
    | "LISTING_STATUS"
    | "AGREEMENT"
    | "VERIFICATION"
    | "SYSTEM";
  title: string | null;
  body: string | null;
  data: string; // JSON string, e.g. {"conversationId":"…"}
  readAt: string | null;
  createdAt: string;
}

export function fetchNotifications() {
  return apiFetch<{ items: AppNotification[]; unread: number }>("/api/v1/notifications");
}

export function fetchUnreadCount() {
  return apiFetch<{ unread: number }>("/api/v1/notifications/unread-count");
}

export function markNotificationRead(id: string) {
  return apiPost<{ read: boolean }>(`/api/v1/notifications/${id}/read`, {});
}

export function markAllNotificationsRead() {
  return apiPost<{ read: number }>("/api/v1/notifications/read-all", {});
}

/** Where tapping a notification should take the user, from its data payload. */
export function notificationHref(n: AppNotification): string {
  try {
    const data = JSON.parse(n.data) as Record<string, string>;
    if (data.conversationId) return `/messages/${data.conversationId}`;
    if (data.agreementId) return `/agreements/${data.agreementId}`;
    if (data.listingId) return `/listing/${data.listingId}`;
    if (data.savedSearchId) return "/saved";
  } catch {
    // fall through
  }
  return "/notifications";
}

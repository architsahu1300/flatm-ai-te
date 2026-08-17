"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { formatRelativeTime } from "@/lib/domain";
import {
  fetchNotifications,
  markAllNotificationsRead,
  markNotificationRead,
  notificationHref,
  type AppNotification,
} from "@/lib/notifications-client";
import { cn } from "@/lib/utils";

const TYPE_ICON: Record<AppNotification["type"], string> = {
  MESSAGE: "💬",
  MESSAGE_REQUEST: "✉️",
  SAVED_SEARCH_ALERT: "✦",
  LISTING_STATUS: "🏠",
  AGREEMENT: "📄",
  VERIFICATION: "🪪",
  SYSTEM: "🔔",
};

export function NotificationsScreen() {
  const router = useRouter();
  const [items, setItems] = useState<AppNotification[] | null>(null);
  const [unread, setUnread] = useState(0);

  useEffect(() => {
    fetchNotifications()
      .then((d) => {
        setItems(d.items);
        setUnread(d.unread);
      })
      .catch(() => setItems([]));
  }, []);

  async function open(n: AppNotification) {
    if (!n.readAt) {
      markNotificationRead(n.id).catch(() => {});
      setItems((prev) =>
        (prev ?? []).map((x) => (x.id === n.id ? { ...x, readAt: new Date().toISOString() } : x)),
      );
      setUnread((u) => Math.max(0, u - 1));
    }
    router.push(notificationHref(n));
  }

  async function readAll() {
    await markAllNotificationsRead().catch(() => {});
    const now = new Date().toISOString();
    setItems((prev) => (prev ?? []).map((n) => (n.readAt ? n : { ...n, readAt: now })));
    setUnread(0);
  }

  return (
    <div className="mx-auto max-w-2xl">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold tracking-tight">Notifications</h1>
        {unread > 0 && (
          <Button variant="ghost" size="sm" onClick={readAll}>
            Mark all as read
          </Button>
        )}
      </div>

      <div className="mt-4 space-y-2">
        {items === null ? (
          <>
            <Skeleton className="h-16 rounded-card" />
            <Skeleton className="h-16 rounded-card" />
            <Skeleton className="h-16 rounded-card" />
          </>
        ) : items.length === 0 ? (
          <div className="rounded-card border border-border bg-surface p-10 text-center shadow-card">
            <p className="text-3xl" aria-hidden>🔕</p>
            <p className="mt-2 font-medium">Nothing yet</p>
            <p className="mt-1 text-sm text-text-muted">
              Message requests, saved-search matches and agreement updates land here.
            </p>
          </div>
        ) : (
          items.map((n) => (
            <button
              key={n.id}
              type="button"
              onClick={() => open(n)}
              className={cn(
                "flex w-full cursor-pointer items-start gap-3 rounded-card border border-border p-4 text-left shadow-card transition-colors hover:border-brand",
                n.readAt ? "bg-surface" : "bg-brand-soft/40",
              )}
            >
              <span aria-hidden className="mt-0.5 text-xl">{TYPE_ICON[n.type]}</span>
              <span className="min-w-0 flex-1">
                <span className="flex items-baseline justify-between gap-2">
                  <span className={cn("truncate text-sm", !n.readAt && "font-semibold")}>
                    {n.title ?? "Notification"}
                  </span>
                  <span className="shrink-0 text-xs text-text-muted">
                    {formatRelativeTime(n.createdAt)}
                  </span>
                </span>
                {n.body && (
                  <span className="mt-0.5 line-clamp-2 block text-sm text-text-muted">{n.body}</span>
                )}
              </span>
              {!n.readAt && <span aria-hidden className="mt-2 h-2 w-2 shrink-0 rounded-full bg-brand" />}
            </button>
          ))
        )}
      </div>
    </div>
  );
}

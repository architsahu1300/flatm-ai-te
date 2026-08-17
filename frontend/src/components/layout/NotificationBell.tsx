"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useState } from "react";
import { fetchUnreadCount } from "@/lib/notifications-client";

export function NotificationBell() {
  const pathname = usePathname();
  const [unread, setUnread] = useState(0);

  useEffect(() => {
    let cancelled = false;
    const load = () =>
      fetchUnreadCount()
        .then((d) => !cancelled && setUnread(d.unread))
        .catch(() => {});
    load();
    const t = setInterval(load, 30_000);
    return () => {
      cancelled = true;
      clearInterval(t);
    };
    // refetch when navigating (e.g. leaving /notifications after reading)
  }, [pathname]);

  return (
    <Link
      href="/notifications"
      aria-label={unread > 0 ? `Notifications, ${unread} unread` : "Notifications"}
      className="relative flex h-9 w-9 items-center justify-center rounded-chip text-lg transition-colors hover:bg-surface-2"
    >
      🔔
      {unread > 0 && (
        <span className="tnum absolute -right-0.5 -top-0.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-danger px-1 text-[10px] font-bold text-white">
          {unread > 9 ? "9+" : unread}
        </span>
      )}
    </Link>
  );
}

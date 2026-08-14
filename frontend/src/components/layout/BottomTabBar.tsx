"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { cn } from "@/lib/utils";

const TABS = [
  { href: "/", label: "Home", icon: "🏠", exact: true },
  { href: "/explore", label: "Explore", icon: "🧭" },
  { href: "/search", label: "Search", icon: "✦", primary: true },
  { href: "/messages", label: "Messages", icon: "💬" },
  { href: "/profile", label: "Profile", icon: "👤" },
];

export function BottomTabBar() {
  const pathname = usePathname();
  // Hidden inside conversation threads (composer owns the bottom)
  if (/^\/messages\/[^/]+/.test(pathname)) {
    return null;
  }
  return (
    <nav
      className="fixed inset-x-0 bottom-0 z-40 border-t border-border bg-surface/95 backdrop-blur md:hidden"
      style={{ paddingBottom: "env(safe-area-inset-bottom)" }}
      aria-label="Primary"
    >
      <div className="flex h-14 items-stretch">
        {TABS.map((tab) => {
          const active = tab.exact ? pathname === tab.href : pathname.startsWith(tab.href);
          if (tab.primary) {
            return (
              <Link key={tab.href} href={tab.href} className="relative flex flex-1 items-end justify-center pb-1">
                <span
                  className={cn(
                    "absolute -top-4 flex h-12 w-12 items-center justify-center rounded-chip text-xl text-white shadow-pop",
                    active ? "bg-brand-hover" : "bg-brand",
                  )}
                >
                  {tab.icon}
                </span>
                <span className={cn("text-[10px] font-medium", active ? "text-brand" : "text-text-muted")}>
                  {tab.label}
                </span>
              </Link>
            );
          }
          return (
            <Link
              key={tab.href}
              href={tab.href}
              className={cn(
                "flex flex-1 flex-col items-center justify-center gap-0.5 text-[10px] font-medium",
                active ? "text-brand" : "text-text-muted",
              )}
            >
              <span className="text-lg leading-none">{tab.icon}</span>
              {tab.label}
            </Link>
          );
        })}
      </div>
    </nav>
  );
}

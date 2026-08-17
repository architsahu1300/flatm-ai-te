"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { cn } from "@/lib/utils";

const ITEMS: { href: string; label: string; icon: string }[] = [
  { href: "/admin", label: "Overview", icon: "📊" },
  { href: "/admin/users", label: "Users", icon: "👥" },
  { href: "/admin/listings", label: "Listings", icon: "🏠" },
  { href: "/admin/reports", label: "Reports", icon: "🚩" },
  { href: "/admin/verifications", label: "Verifications", icon: "🪪" },
  { href: "/admin/ai-usage", label: "AI usage", icon: "✨" },
];

export function AdminNav() {
  const pathname = usePathname();
  return (
    <nav
      aria-label="Admin"
      className="flex gap-1 overflow-x-auto pb-1 lg:w-52 lg:flex-col lg:gap-0.5 lg:overflow-visible lg:pb-0"
    >
      <p className="hidden px-3 pb-2 text-xs font-semibold uppercase tracking-wider text-text-muted lg:block">
        Admin
      </p>
      {ITEMS.map(({ href, label, icon }) => {
        const active = pathname === href;
        return (
          <Link
            key={href}
            href={href}
            aria-current={active ? "page" : undefined}
            className={cn(
              "flex shrink-0 items-center gap-2 rounded-control px-3 py-2 text-sm transition-colors",
              active
                ? "bg-brand-soft font-medium text-brand"
                : "text-text-muted hover:bg-surface-2 hover:text-text",
            )}
          >
            <span aria-hidden>{icon}</span>
            {label}
          </Link>
        );
      })}
    </nav>
  );
}

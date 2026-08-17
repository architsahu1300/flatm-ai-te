"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { Avatar } from "@/components/ui/avatar";
import { NotificationBell } from "@/components/layout/NotificationBell";
import { ThemeToggle } from "@/components/layout/ThemeToggle";
import { logout, type SessionUser } from "@/lib/auth-client";
import { Wordmark } from "@/lib/brand";
import { cn } from "@/lib/utils";
import { useEffect, useRef, useState } from "react";
import { useAiSearchStore } from "@/stores/ai-search-store";

const LINKS = [
  { href: "/explore", label: "Explore" },
  { href: "/flatmates", label: "Flatmates" },
  { href: "/messages", label: "Messages" },
  { href: "/saved", label: "Saved" },
  { href: "/agreements", label: "Agreements" },
];

export function TopNav({ user }: { user: SessionUser | null }) {
  const pathname = usePathname();
  const router = useRouter();
  const [menuOpen, setMenuOpen] = useState(false);
  const [navQuery, setNavQuery] = useState("");
  const [navFocused, setNavFocused] = useState(false);
  const navInput = useRef<HTMLInputElement>(null);
  const submitSearch = useAiSearchStore((s) => s.submit);

  // ⌘K / Ctrl+K — jump to the AI search from anywhere
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") {
        e.preventDefault();
        if (navInput.current) {
          navInput.current.focus();
        } else {
          router.push("/search");
        }
      }
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [router]);

  async function handleLogout() {
    await logout();
    router.push("/");
    router.refresh();
  }

  return (
    <header className="sticky top-0 z-40 hidden h-16 border-b border-border bg-surface/80 backdrop-blur md:block">
      <div className="mx-auto flex h-full max-w-7xl items-center gap-6 px-6">
        <Link href="/" className="text-lg font-bold tracking-tight">
          <Wordmark />
        </Link>

        <form
          onSubmit={(e) => {
            e.preventDefault();
            const q = navQuery.trim();
            if (q.length < 3) return;
            setNavQuery("");
            navInput.current?.blur();
            // already on /search? run it in place — otherwise hand off through the URL
            if (pathname === "/search") {
              submitSearch(q);
            } else {
              router.push(`/search?q=${encodeURIComponent(q)}`);
            }
          }}
          className={cn(
            "flex h-10 w-72 items-center gap-2 rounded-chip border bg-surface px-4 text-sm transition-colors",
            navFocused ? "border-brand" : "border-border hover:border-brand",
          )}
        >
          <span className="text-brand">✦</span>
          <input
            ref={navInput}
            value={navQuery}
            onChange={(e) => setNavQuery(e.target.value)}
            onFocus={() => setNavFocused(true)}
            onBlur={() => setNavFocused(false)}
            placeholder="What are you looking for?"
            aria-label="Search homes and flatmates"
            className="min-w-0 flex-1 bg-transparent text-text placeholder:text-text-muted"
          />
          {navQuery.trim().length === 0 && (
            <kbd className="shrink-0 rounded border border-border bg-surface-2 px-1.5 text-[11px] text-text-muted">
              ⌘K
            </kbd>
          )}
        </form>

        <nav className="flex items-center gap-1 text-sm font-medium">
          {LINKS.map((link) => (
            <Link
              key={link.href}
              href={link.href}
              className={cn(
                "rounded-control px-3 py-2 transition-colors",
                pathname.startsWith(link.href)
                  ? "text-brand"
                  : "text-text-muted hover:bg-surface-2 hover:text-text",
              )}
            >
              {link.label}
            </Link>
          ))}
        </nav>

        <div className="ml-auto flex items-center gap-3">
          <ThemeToggle compact />
          {user && <NotificationBell />}
          <Link
            href="/my-listings/new"
            className="rounded-control border border-border px-3 py-2 text-sm font-medium transition-colors hover:bg-surface-2"
          >
            + List your place
          </Link>
          {user ? (
            <div className="relative">
              <button
                type="button"
                onClick={() => setMenuOpen((o) => !o)}
                className="cursor-pointer rounded-chip"
                aria-label="Account menu"
              >
                <Avatar name={user.name} src={null} size={36} />
              </button>
              {menuOpen && (
                <div
                  className="absolute right-0 top-11 w-48 rounded-card border border-border bg-surface p-1.5 shadow-pop"
                  onMouseLeave={() => setMenuOpen(false)}
                >
                  <div className="border-b border-border px-3 py-2">
                    <p className="truncate text-sm font-medium">{user.name}</p>
                    <p className="truncate text-xs text-text-muted">{user.email ?? user.phone}</p>
                  </div>
                  {[
                    { href: "/profile", label: "Profile" },
                    { href: "/my-listings", label: "My listings" },
                    { href: "/notifications", label: "Notifications" },
                    { href: "/plans", label: "Plans & boost" },
                    ...(user.role === "ADMIN" ? [{ href: "/admin", label: "Admin" }] : []),
                  ].map((item) => (
                    <Link
                      key={item.href}
                      href={item.href}
                      onClick={() => setMenuOpen(false)}
                      className="block rounded-control px-3 py-2 text-sm hover:bg-surface-2"
                    >
                      {item.label}
                    </Link>
                  ))}
                  <button
                    type="button"
                    onClick={handleLogout}
                    className="block w-full cursor-pointer rounded-control px-3 py-2 text-left text-sm text-danger hover:bg-surface-2"
                  >
                    Sign out
                  </button>
                </div>
              )}
            </div>
          ) : (
            <Link
              href="/signin"
              className="rounded-control bg-brand px-4 py-2 text-sm font-medium text-white hover:bg-brand-hover"
            >
              Sign in
            </Link>
          )}
        </div>
      </div>
    </header>
  );
}

"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { Avatar } from "@/components/ui/avatar";
import { ThemeToggle } from "@/components/layout/ThemeToggle";
import { logout, type SessionUser } from "@/lib/auth-client";
import { Wordmark } from "@/lib/brand";
import { cn } from "@/lib/utils";
import { useEffect, useState } from "react";

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

  // ⌘K / Ctrl+K — jump to the AI search from anywhere
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") {
        e.preventDefault();
        router.push("/search");
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

        <Link
          href="/search"
          className="flex h-10 w-72 items-center gap-2 rounded-chip border border-border bg-surface px-4 text-sm text-text-muted transition-colors hover:border-brand"
        >
          <span className="text-brand">✦</span> What are you looking for?
          <kbd className="ml-auto rounded border border-border bg-surface-2 px-1.5 text-[11px]">⌘K</kbd>
        </Link>

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

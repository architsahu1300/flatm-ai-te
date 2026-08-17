"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { Skeleton } from "@/components/ui/skeleton";
import { fetchStats, type AdminStats } from "@/lib/admin-client";
import { StatTile } from "./admin-bits";

const FUNNEL_STAGES: { key: keyof AdminStats["funnel"]; label: string; icon: string }[] = [
  { key: "ai_searches", label: "AI searches", icon: "🔍" },
  { key: "search_sessions", label: "Search sessions", icon: "💬" },
  { key: "contacts", label: "Contacts initiated", icon: "✉️" },
  { key: "connections", label: "Connections", icon: "🤝" },
  { key: "agreements", label: "Agreements", icon: "📄" },
];

export function OverviewScreen() {
  const [stats, setStats] = useState<AdminStats | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetchStats().then(setStats).catch((e) => setError(e instanceof Error ? e.message : "Failed to load"));
  }, []);

  if (error) {
    return <p className="rounded-card bg-danger-soft p-4 text-sm text-danger">{error}</p>;
  }
  if (!stats) {
    return (
      <div className="space-y-4">
        <Skeleton className="h-28 rounded-card" />
        <Skeleton className="h-64 rounded-card" />
      </div>
    );
  }

  const { counts, funnel, aiToday } = stats;
  const funnelBase = Math.max(funnel.ai_searches, 1);

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold tracking-tight">Overview</h1>

      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <StatTile label="Users" value={counts.users} hint={`${counts.suspended_users} suspended`} />
        <StatTile
          label="Active listings"
          value={counts.active_listings}
          hint={`${counts.flatmate_cards} flatmate cards live`}
        />
        <StatTile
          label="Suspicious listings"
          value={counts.suspicious_listings}
          hint="scam risk ≥ 0.5"
          tone={counts.suspicious_listings > 0 ? "warning" : undefined}
        />
        <StatTile
          label="Open reports"
          value={counts.open_reports}
          hint={`${counts.pending_verifications} verifications pending`}
          tone={counts.open_reports > 0 ? "danger" : undefined}
        />
      </div>

      <section className="rounded-card border border-border bg-surface p-5 shadow-card">
        <h2 className="font-semibold">Conversion funnel</h2>
        <p className="text-sm text-text-muted">From AI search to signed agreement, all time.</p>
        <ol className="mt-4 space-y-2.5">
          {FUNNEL_STAGES.map(({ key, label, icon }, i) => {
            const value = funnel[key];
            const pct = Math.round((value / funnelBase) * 100);
            return (
              <li key={key} className="flex items-center gap-3">
                <span aria-hidden className="w-6 text-center">{icon}</span>
                <div className="min-w-0 flex-1">
                  <div className="flex items-baseline justify-between gap-2">
                    <p className="text-sm">
                      {i + 1}. {label}
                    </p>
                    <p className="tnum text-sm font-medium">
                      {value.toLocaleString("en-IN")}
                      <span className="ml-1.5 text-xs text-text-muted">{pct}%</span>
                    </p>
                  </div>
                  <div className="mt-1 h-1.5 overflow-hidden rounded-full bg-surface-2">
                    <div
                      className="h-full rounded-full bg-brand"
                      style={{ width: `${Math.max(pct, 2)}%` }}
                    />
                  </div>
                </div>
              </li>
            );
          })}
        </ol>
      </section>

      <div className="grid gap-3 sm:grid-cols-2">
        <section className="rounded-card border border-border bg-surface p-5 shadow-card">
          <h2 className="font-semibold">Marketplace</h2>
          <dl className="mt-3 space-y-2 text-sm">
            <div className="flex justify-between">
              <dt className="text-text-muted">Conversations</dt>
              <dd className="tnum font-medium">{counts.conversations}</dd>
            </div>
            <div className="flex justify-between">
              <dt className="text-text-muted">Messages</dt>
              <dd className="tnum font-medium">{counts.messages}</dd>
            </div>
            <div className="flex justify-between">
              <dt className="text-text-muted">Agreements (signed)</dt>
              <dd className="tnum font-medium">
                {counts.agreements} ({counts.signed_agreements})
              </dd>
            </div>
            <div className="flex justify-between">
              <dt className="text-text-muted">Saved searches</dt>
              <dd className="tnum font-medium">{counts.saved_searches}</dd>
            </div>
          </dl>
        </section>

        <section className="rounded-card border border-border bg-surface p-5 shadow-card">
          <h2 className="font-semibold">AI today</h2>
          <dl className="mt-3 space-y-2 text-sm">
            <div className="flex justify-between">
              <dt className="text-text-muted">Calls</dt>
              <dd className="tnum font-medium">{aiToday.calls}</dd>
            </div>
            <div className="flex justify-between">
              <dt className="text-text-muted">Spend</dt>
              <dd className="tnum font-medium">${Number(aiToday.cost_usd).toFixed(4)}</dd>
            </div>
          </dl>
          <Link href="/admin/ai-usage" className="mt-3 inline-block text-sm text-brand hover:underline">
            Full usage report →
          </Link>
        </section>
      </div>
    </div>
  );
}

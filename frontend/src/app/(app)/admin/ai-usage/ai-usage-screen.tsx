"use client";

import { useEffect, useState } from "react";
import { Skeleton } from "@/components/ui/skeleton";
import { fetchAiUsage, type AiUsage } from "@/lib/admin-client";
import { EmptyRow, StatTile, TableCard, Td, Th } from "../admin-bits";

function tokens(n: number | null): string {
  if (n == null) return "—";
  return n >= 1000 ? `${(n / 1000).toFixed(1)}k` : String(n);
}

export function AiUsageScreen() {
  const [usage, setUsage] = useState<AiUsage | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetchAiUsage().then(setUsage).catch((e) => setError(e instanceof Error ? e.message : "Failed to load"));
  }, []);

  if (error) {
    return <p className="rounded-card bg-danger-soft p-4 text-sm text-danger">{error}</p>;
  }
  if (!usage) {
    return (
      <div className="space-y-4">
        <Skeleton className="h-24 rounded-card" />
        <Skeleton className="h-64 rounded-card" />
      </div>
    );
  }

  const totalCost = usage.byDay.reduce((s, d) => s + Number(d.cost_usd), 0);
  const totalCalls = usage.byDay.reduce((s, d) => s + Number(d.calls), 0);
  const cacheHits = usage.byDay.reduce((s, d) => s + Number(d.cache_hits), 0);
  const hitRate = totalCalls > 0 ? Math.round((cacheHits / totalCalls) * 100) : 0;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">AI usage</h1>
        <p className="text-sm text-text-muted">Token consumption and spend, last 14 days.</p>
      </div>

      <div className="grid grid-cols-3 gap-3">
        <StatTile label="Spend (14d)" value={`$${totalCost.toFixed(4)}`} />
        <StatTile label="Calls (14d)" value={totalCalls.toLocaleString("en-IN")} />
        <StatTile label="Cache hit rate" value={`${hitRate}%`} hint={`${cacheHits} cached`} />
      </div>

      <TableCard title="Daily breakdown">
        <table className="w-full min-w-[640px]">
          <thead>
            <tr className="border-b border-border">
              <Th>Date</Th>
              <Th className="text-right">Calls</Th>
              <Th className="text-right">Prompt tokens</Th>
              <Th className="text-right">Completion tokens</Th>
              <Th className="text-right">Cache hits</Th>
              <Th className="text-right">Cost (USD)</Th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border">
            {usage.byDay.length === 0 && <EmptyRow colSpan={6} text="No AI calls yet." />}
            {usage.byDay.map((d) => (
              <tr key={d.day}>
                <Td className="whitespace-nowrap font-medium">
                  {new Date(d.day).toLocaleDateString("en-IN", { day: "numeric", month: "short" })}
                </Td>
                <Td className="tnum text-right">{d.calls}</Td>
                <Td className="tnum text-right">{tokens(d.prompt_tokens)}</Td>
                <Td className="tnum text-right">{tokens(d.completion_tokens)}</Td>
                <Td className="tnum text-right">{d.cache_hits}</Td>
                <Td className="tnum text-right">${Number(d.cost_usd).toFixed(4)}</Td>
              </tr>
            ))}
          </tbody>
        </table>
      </TableCard>

      <TableCard title="By feature (all time)">
        <table className="w-full min-w-[520px]">
          <thead>
            <tr className="border-b border-border">
              <Th>Feature</Th>
              <Th>Provider</Th>
              <Th className="text-right">Calls</Th>
              <Th className="text-right">Cost (USD)</Th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border">
            {usage.byFeature.length === 0 && <EmptyRow colSpan={4} text="No AI calls yet." />}
            {usage.byFeature.map((f) => (
              <tr key={`${f.feature}-${f.provider}`}>
                <Td className="font-medium">{f.feature.replace(/_/g, " ").toLowerCase()}</Td>
                <Td className="text-text-muted">{f.provider ?? "—"}</Td>
                <Td className="tnum text-right">{f.calls}</Td>
                <Td className="tnum text-right">${Number(f.cost_usd).toFixed(4)}</Td>
              </tr>
            ))}
          </tbody>
        </table>
      </TableCard>
    </div>
  );
}

"use client";

import { cn } from "@/lib/utils";

export function StatTile({
  label,
  value,
  hint,
  tone,
}: {
  label: string;
  value: string | number;
  hint?: string;
  tone?: "warning" | "danger";
}) {
  return (
    <div className="rounded-card border border-border bg-surface p-4 shadow-card">
      <p className="text-xs text-text-muted">{label}</p>
      <p
        className={cn(
          "tnum mt-1 text-2xl font-bold tracking-tight",
          tone === "warning" && "text-warning",
          tone === "danger" && "text-danger",
        )}
      >
        {value}
      </p>
      {hint && <p className="mt-0.5 text-xs text-text-muted">{hint}</p>}
    </div>
  );
}

/** Bordered card with a horizontally scrollable table — every admin list uses this shell. */
export function TableCard({
  title,
  action,
  children,
}: {
  title: string;
  action?: React.ReactNode;
  children: React.ReactNode;
}) {
  return (
    <section className="rounded-card border border-border bg-surface shadow-card">
      <div className="flex flex-wrap items-center justify-between gap-3 border-b border-border px-4 py-3">
        <h2 className="font-semibold">{title}</h2>
        {action}
      </div>
      <div className="overflow-x-auto">{children}</div>
    </section>
  );
}

export function Th({ children, className }: { children?: React.ReactNode; className?: string }) {
  return (
    <th
      className={cn(
        "whitespace-nowrap px-4 py-2.5 text-left text-xs font-semibold uppercase tracking-wider text-text-muted",
        className,
      )}
    >
      {children}
    </th>
  );
}

export function Td({ children, className }: { children?: React.ReactNode; className?: string }) {
  return <td className={cn("px-4 py-3 align-top text-sm", className)}>{children}</td>;
}

export function EmptyRow({ colSpan, text }: { colSpan: number; text: string }) {
  return (
    <tr>
      <td colSpan={colSpan} className="px-4 py-10 text-center text-sm text-text-muted">
        {text}
      </td>
    </tr>
  );
}

export function riskTone(score: number): string {
  if (score >= 0.5) return "text-danger";
  if (score >= 0.3) return "text-warning";
  return "text-text-muted";
}

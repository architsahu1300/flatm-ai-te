"use client";

import { cn } from "@/lib/utils";

interface ChipSelectProps<T extends string> {
  options: readonly { value: T; label: string }[];
  value: T | T[] | null;
  onChange: (value: T) => void;
  multi?: boolean;
  className?: string;
}

export function ChipSelect<T extends string>({
  options,
  value,
  onChange,
  multi = false,
  className,
}: ChipSelectProps<T>) {
  const selected = new Set(Array.isArray(value) ? value : value ? [value] : []);
  return (
    <div className={cn("flex flex-wrap gap-2", className)} role={multi ? "group" : "radiogroup"}>
      {options.map((opt) => {
        const active = selected.has(opt.value);
        return (
          <button
            key={opt.value}
            type="button"
            role={multi ? "checkbox" : "radio"}
            aria-checked={active}
            onClick={() => onChange(opt.value)}
            className={cn(
              "rounded-chip border px-3.5 py-1.5 text-sm font-medium transition-colors cursor-pointer",
              active
                ? "border-brand bg-brand-soft text-brand"
                : "border-border bg-surface text-text-muted hover:border-text-muted",
            )}
          >
            {opt.label}
          </button>
        );
      })}
    </div>
  );
}

export function toOptions<T extends string>(labels: Record<T, string>) {
  return (Object.entries(labels) as [T, string][]).map(([value, label]) => ({ value, label }));
}

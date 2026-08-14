"use client";

import { useEffect, useState } from "react";
import { applyTheme, readThemeChoice, type ThemeChoice } from "@/lib/theme";
import { cn } from "@/lib/utils";

const OPTIONS: { value: ThemeChoice; icon: string; label: string }[] = [
  { value: "light", icon: "☀", label: "Light" },
  { value: "system", icon: "◐", label: "System" },
  { value: "dark", icon: "☾", label: "Dark" },
];

/** Three-way theme control. `compact` renders a single cycling button for tight spaces. */
export function ThemeToggle({ compact = false }: { compact?: boolean }) {
  const [choice, setChoice] = useState<ThemeChoice>("system");
  const [mounted, setMounted] = useState(false);

  useEffect(() => {
    setChoice(readThemeChoice());
    setMounted(true);
  }, []);

  // Follow the OS while on "system"
  useEffect(() => {
    if (choice !== "system") return;
    const mq = window.matchMedia("(prefers-color-scheme: dark)");
    const onChange = () => applyTheme("system");
    mq.addEventListener("change", onChange);
    return () => mq.removeEventListener("change", onChange);
  }, [choice]);

  function pick(next: ThemeChoice) {
    setChoice(next);
    applyTheme(next);
  }

  // Render a stable placeholder until mounted — avoids a hydration mismatch,
  // since the real value only exists in localStorage/matchMedia.
  if (!mounted) {
    return <div className={compact ? "h-9 w-9" : "h-9 w-[104px]"} aria-hidden />;
  }

  if (compact) {
    const order: ThemeChoice[] = ["light", "dark", "system"];
    const current = OPTIONS.find((o) => o.value === choice)!;
    return (
      <button
        type="button"
        onClick={() => pick(order[(order.indexOf(choice) + 1) % order.length])}
        aria-label={`Theme: ${current.label}. Click to change.`}
        title={`Theme: ${current.label}`}
        className="flex h-9 w-9 cursor-pointer items-center justify-center rounded-control text-base text-text-muted transition-colors hover:bg-surface-2 hover:text-text"
      >
        {current.icon}
      </button>
    );
  }

  return (
    <div
      role="radiogroup"
      aria-label="Colour theme"
      className="inline-flex rounded-control border border-border bg-surface p-0.5"
    >
      {OPTIONS.map((opt) => (
        <button
          key={opt.value}
          type="button"
          role="radio"
          aria-checked={choice === opt.value}
          aria-label={opt.label}
          title={opt.label}
          onClick={() => pick(opt.value)}
          className={cn(
            "flex h-8 w-8 cursor-pointer items-center justify-center rounded-[calc(var(--radius-control)-3px)] text-sm transition-colors",
            choice === opt.value
              ? "bg-brand-soft text-brand"
              : "text-text-muted hover:text-text",
          )}
        >
          {opt.icon}
        </button>
      ))}
    </div>
  );
}

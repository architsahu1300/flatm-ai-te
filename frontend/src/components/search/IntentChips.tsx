"use client";

import { useEffect, useState } from "react";
import { chipsFromIntent, type SearchIntent } from "@/lib/ai-client";
import { cn } from "@/lib/utils";

/**
 * The signature interaction: the AI's interpretation as editable chips. Chips stagger in after
 * "Understanding…", every chip is removable (✕ = intent surgery + deterministic re-search).
 */
export function IntentChips({
  intent,
  animateKey,
  onRemove,
  disabled,
}: {
  intent: SearchIntent;
  animateKey: string;
  onRemove: (nextIntent: SearchIntent, note: string) => void;
  disabled?: boolean;
}) {
  const chips = chipsFromIntent(intent);
  const [revealed, setRevealed] = useState(0);

  useEffect(() => {
    setRevealed(0);
    let i = 0;
    const t = setInterval(() => {
      i += 1;
      setRevealed(i);
      if (i >= chips.length) clearInterval(t);
    }, 80);
    return () => clearInterval(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [animateKey]);

  return (
    <div className="flex flex-wrap items-center gap-2" role="group" aria-label="Interpreted requirements">
      {chips.map((chip, i) => (
        <span
          key={chip.key}
          className={cn(
            "inline-flex items-center gap-1.5 rounded-chip border border-border bg-surface py-1 pl-2.5 pr-1.5 text-[13px] shadow-sm transition-all duration-200",
            i < revealed ? "scale-100 opacity-100" : "scale-90 opacity-0",
          )}
        >
          <span aria-hidden>{chip.icon}</span>
          <span className="text-text-muted">{chip.label}:</span>
          <span className="font-medium text-text">{chip.value}</span>
          <button
            type="button"
            disabled={disabled}
            onClick={() => onRemove(chip.remove(intent), `Removed ${chip.label.toLowerCase()} "${chip.value}"`)}
            aria-label={`Remove ${chip.label} ${chip.value}`}
            className="ml-0.5 cursor-pointer rounded-chip px-1 text-text-muted transition-colors hover:bg-danger-soft hover:text-danger"
          >
            ✕
          </button>
        </span>
      ))}
    </div>
  );
}

"use client";

import { useEffect, useRef, useState } from "react";
import { Spinner } from "@/components/ui/spinner";
import { cn } from "@/lib/utils";

export const EXAMPLE_QUERIES = [
  "Private room in BKC under ₹25k, quiet flat, no smoking",
  "Furnished 2BHK in Andheri or Powai under ₹60k",
  "Find a quiet flatmate who works in Andheri",
  "Shared room near Ghatkopar, moving in next month",
  "Vegetarian household in Malad, budget ₹15k",
];

export function useRotatingPlaceholder(paused: boolean) {
  const [index, setIndex] = useState(0);
  useEffect(() => {
    if (paused) return;
    const t = setInterval(() => setIndex((i) => (i + 1) % EXAMPLE_QUERIES.length), 4500);
    return () => clearInterval(t);
  }, [paused]);
  return EXAMPLE_QUERIES[index];
}

export function SearchBox({
  onSubmit,
  busy,
  size = "lg",
  autoFocus,
  initialValue = "",
}: {
  onSubmit: (query: string) => void;
  busy?: boolean;
  size?: "lg" | "md";
  autoFocus?: boolean;
  initialValue?: string;
}) {
  const [value, setValue] = useState(initialValue);
  const [focused, setFocused] = useState(false);
  const placeholder = useRotatingPlaceholder(focused || value.length > 0);
  const ref = useRef<HTMLTextAreaElement>(null);

  useEffect(() => {
    if (autoFocus) ref.current?.focus();
  }, [autoFocus]);

  function submit() {
    const q = value.trim();
    if (q.length >= 3 && !busy) {
      onSubmit(q);
    }
  }

  return (
    <div
      className={cn(
        "flex items-start gap-3 rounded-card border bg-surface transition-all",
        focused ? "border-brand shadow-pop" : "border-border shadow-card",
        size === "lg" ? "p-4" : "p-3",
      )}
    >
      <span className={cn("shrink-0 text-brand", size === "lg" ? "mt-0.5 text-xl" : "text-base")}>✦</span>
      <textarea
        ref={ref}
        rows={1}
        value={value}
        placeholder={placeholder}
        aria-label="What are you looking for?"
        onChange={(e) => {
          setValue(e.target.value);
          e.target.style.height = "auto";
          e.target.style.height = Math.min(e.target.scrollHeight, 120) + "px";
        }}
        onFocus={() => setFocused(true)}
        onBlur={() => setFocused(false)}
        onKeyDown={(e) => {
          if (e.key === "Enter" && !e.shiftKey) {
            e.preventDefault();
            submit();
          }
        }}
        className={cn(
          "min-w-0 flex-1 resize-none bg-transparent text-text placeholder:text-text-muted",
          "outline-none focus:outline-none focus-visible:outline-none",
          size === "lg" ? "text-base leading-6" : "text-sm leading-5",
        )}
      />
      <button
        type="button"
        onClick={submit}
        disabled={busy || value.trim().length < 3}
        aria-label="Search"
        className={cn(
          "flex shrink-0 cursor-pointer items-center justify-center rounded-chip bg-brand text-white transition-colors hover:bg-brand-hover disabled:opacity-40",
          size === "lg" ? "h-10 w-10" : "h-8 w-8",
        )}
      >
        {busy ? <Spinner /> : "→"}
      </button>
    </div>
  );
}

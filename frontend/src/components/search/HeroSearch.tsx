"use client";

import { useRouter } from "next/navigation";
import { EXAMPLE_QUERIES, SearchBox } from "@/components/search/SearchBox";

export function HeroSearch() {
  const router = useRouter();

  function go(query: string) {
    router.push(`/search?q=${encodeURIComponent(query)}`);
  }

  return (
    <div className="mx-auto w-full max-w-xl">
      <SearchBox onSubmit={go} />
      <div className="mt-4 flex flex-wrap justify-center gap-2">
        {EXAMPLE_QUERIES.slice(0, 3).map((q) => (
          <button
            key={q}
            type="button"
            onClick={() => go(q)}
            className="cursor-pointer rounded-chip border border-border bg-surface px-3 py-1.5 text-[13px] text-text-muted transition-colors hover:border-brand hover:text-brand"
          >
            {q.length > 44 ? q.slice(0, 42) + "…" : q}
          </button>
        ))}
      </div>
    </div>
  );
}

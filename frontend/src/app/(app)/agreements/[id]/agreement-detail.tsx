"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { ApiError } from "@/lib/api";
import { formatINR } from "@/lib/domain";
import {
  cancelAgreement,
  fetchAgreement,
  finalizeAgreement,
  signAgreement,
  suggestClauses,
  updateAgreement,
  type Agreement,
  type Clause,
} from "@/lib/agreements-client";
import { STATUS_STYLES } from "../agreements-screen";
import { cn } from "@/lib/utils";

const TIMELINE: { key: string; label: string }[] = [
  { key: "DRAFT", label: "Draft" },
  { key: "FINALIZED", label: "Finalized" },
  { key: "SIGNED", label: "Signed" },
];

export function AgreementDetail({ agreementId }: { agreementId: string }) {
  const [agreement, setAgreement] = useState<Agreement | null>(null);
  const [suggestions, setSuggestions] = useState<Clause[] | null>(null);
  const [suggesting, setSuggesting] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    fetchAgreement(agreementId).then(setAgreement).catch(() => {});
  }, [agreementId]);

  useEffect(load, [load]);

  async function act(kind: "finalize" | "sign" | "cancel") {
    setBusy(kind);
    setError(null);
    try {
      const updated =
        kind === "finalize"
          ? await finalizeAgreement(agreementId)
          : kind === "sign"
            ? await signAgreement(agreementId)
            : await cancelAgreement(agreementId);
      setAgreement(updated);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Action failed");
    } finally {
      setBusy(null);
    }
  }

  async function getSuggestions() {
    setSuggesting(true);
    setError(null);
    try {
      setSuggestions(await suggestClauses(agreementId));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Could not fetch suggestions");
    } finally {
      setSuggesting(false);
    }
  }

  async function acceptSuggestion(clause: Clause) {
    if (!agreement) return;
    try {
      const updated = await updateAgreement(agreementId, {
        clauses: [...agreement.clauses, clause],
      });
      setAgreement(updated);
      setSuggestions((prev) => prev?.filter((c) => c.id !== clause.id) ?? null);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Could not add clause");
    }
  }

  if (!agreement) {
    return (
      <div className="mx-auto max-w-3xl space-y-4">
        <Skeleton className="h-24 rounded-card" />
        <Skeleton className="h-96 rounded-card" />
      </div>
    );
  }

  const stageIndex =
    agreement.status === "SIGNED" ? 2 : agreement.status === "FINALIZED" ? 1 : 0;
  const isCancelled = agreement.status === "CANCELLED";
  const isDraft = agreement.status === "DRAFT";

  return (
    <div className="mx-auto max-w-3xl">
      <Link href="/agreements" className="mb-4 inline-flex items-center gap-1.5 text-sm font-medium text-text-muted hover:text-text">
        <span aria-hidden>←</span> All agreements
      </Link>

      <div className="rounded-card border border-border bg-surface p-6 shadow-card sm:p-8">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="min-w-0">
            <div className="flex items-center gap-2">
              <Badge variant={STATUS_STYLES[agreement.status]}>{agreement.status}</Badge>
              <span className="text-xs text-text-muted">
                v{agreement.currentVersion} · Maharashtra L&amp;L
              </span>
            </div>
            <h1 className="mt-2 text-xl font-semibold tracking-tight">
              {agreement.propertyAddress ?? agreement.listingTitle ?? "Rental agreement"}
            </h1>
          </div>
          <a
            href={`/api/v1/agreements/${agreement.id}/pdf`}
            download
            className="shrink-0 rounded-control border border-border bg-surface px-4 py-2 text-sm font-medium transition-colors hover:bg-surface-2"
          >
            ⬇ Download PDF
          </a>
        </div>

        {/* timeline */}
        {!isCancelled && (
          <ol className="mt-6 flex items-center">
            {TIMELINE.map((stage, i) => (
              <li key={stage.key} className={cn("flex items-center", i < TIMELINE.length - 1 && "flex-1")}>
                <span
                  className={cn(
                    "flex h-7 w-7 shrink-0 items-center justify-center rounded-chip text-xs font-bold",
                    i <= stageIndex ? "bg-brand text-white" : "bg-surface-2 text-text-muted",
                  )}
                >
                  {i < stageIndex || (i === stageIndex && agreement.status === "SIGNED") ? "✓" : i + 1}
                </span>
                <span className={cn("ml-2 text-sm", i <= stageIndex ? "font-medium" : "text-text-muted")}>
                  {stage.label}
                </span>
                {i < TIMELINE.length - 1 && (
                  <span className={cn("mx-3 h-px flex-1", i < stageIndex ? "bg-brand" : "bg-border")} />
                )}
              </li>
            ))}
          </ol>
        )}

        {/* terms */}
        <dl className="mt-6 grid grid-cols-2 gap-x-6 gap-y-3 sm:grid-cols-3">
          {(
            [
              ["Rent", `${formatINR(agreement.rentMonthly)}/mo`],
              ["Deposit", formatINR(agreement.deposit)],
              ["Term", `${agreement.durationMonths} months`],
              ["Starts", new Date(agreement.startDate).toLocaleDateString("en-IN", { day: "numeric", month: "short", year: "numeric" })],
              ["Lock-in", `${agreement.lockInMonths} months`],
              ["Notice", `${agreement.noticePeriodDays} days`],
              ["Escalation", `${agreement.annualEscalationPct}%/yr`],
              ["Landlord", agreement.landlordName],
            ] as const
          ).map(([k, v]) => (
            <div key={k}>
              <dt className="text-xs text-text-muted">{k}</dt>
              <dd className="tnum text-sm font-medium">{v}</dd>
            </div>
          ))}
        </dl>

        {/* signatures */}
        <section className="mt-6">
          <h2 className="text-sm font-semibold uppercase tracking-wide text-text-muted">Signatures</h2>
          <ul className="mt-2 space-y-1.5">
            {agreement.signatures.map((s) => (
              <li key={s.userId} className="flex items-center justify-between rounded-control bg-surface-2 px-3 py-2 text-sm">
                <span>
                  {s.name} <span className="text-text-muted">({s.role})</span>
                </span>
                {s.status === "SIGNED" ? (
                  <span className="text-success">✓ signed {s.signedAt && new Date(s.signedAt).toLocaleDateString("en-IN")}</span>
                ) : (
                  <span className="text-text-muted">pending</span>
                )}
              </li>
            ))}
          </ul>
        </section>

        {/* clauses */}
        <section className="mt-6">
          <h2 className="text-sm font-semibold uppercase tracking-wide text-text-muted">
            Clauses ({agreement.clauses.length})
          </h2>
          <ul className="mt-2 space-y-2">
            {agreement.clauses.map((c, i) => (
              <li key={c.id} className="rounded-control border border-border p-3">
                <p className="text-sm font-medium">
                  {i + 1}. {c.title}
                  {c.source === "ai" && (
                    <span className="ml-2 rounded-chip bg-brand-soft px-1.5 py-0.5 text-[10px] text-brand">
                      AI draft — review before use
                    </span>
                  )}
                </p>
                <p className="mt-1 text-sm leading-relaxed text-text-muted">{c.body}</p>
              </li>
            ))}
          </ul>
        </section>

        {/* AI clause panel — drafts only while editable */}
        {isDraft && (
          <section className="mt-6 rounded-card bg-brand-soft/40 p-4">
            <div className="flex items-center justify-between gap-3">
              <div>
                <p className="text-sm font-medium">✦ AI clause suggestions</p>
                <p className="text-xs text-text-muted">
                  Drafts you can accept one by one — nothing is added without you.
                </p>
              </div>
              <Button variant="outline" size="sm" onClick={getSuggestions} disabled={suggesting}>
                {suggesting ? <Spinner /> : suggestions ? "Refresh" : "Suggest clauses"}
              </Button>
            </div>
            {suggestions && (
              <ul className="mt-3 space-y-2">
                {suggestions.length === 0 && (
                  <p className="text-sm text-text-muted">Nothing more to suggest.</p>
                )}
                {suggestions.map((c) => (
                  <li key={c.id} className="rounded-control border border-border bg-surface p-3">
                    <div className="flex items-start justify-between gap-3">
                      <div className="min-w-0">
                        <p className="text-sm font-medium">{c.title}</p>
                        <p className="mt-1 text-xs leading-relaxed text-text-muted">{c.body}</p>
                      </div>
                      <Button size="sm" onClick={() => acceptSuggestion(c)}>Add</Button>
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </section>
        )}

        {error && <p className="mt-4 text-sm text-danger">{error}</p>}

        {/* actions */}
        <div className="mt-6 flex flex-wrap items-center gap-3 border-t border-border pt-5">
          {agreement.viewerCanFinalize && (
            <Button onClick={() => act("finalize")} disabled={busy !== null}>
              {busy === "finalize" ? <Spinner /> : "Finalize — lock terms & open signing"}
            </Button>
          )}
          {agreement.viewerCanSign && (
            <Button onClick={() => act("sign")} disabled={busy !== null}>
              {busy === "sign" ? <Spinner /> : "✍ Sign (mock e-sign)"}
            </Button>
          )}
          {!isCancelled && agreement.status !== "SIGNED" && (
            <Button variant="ghost" className="text-danger" onClick={() => act("cancel")} disabled={busy !== null}>
              Cancel agreement
            </Button>
          )}
        </div>

        <p className="mt-5 rounded-control bg-warning-soft p-3 text-xs leading-relaxed text-warning">
          ⚠ Generated with software assistance — not legal advice. Stamp duty and registration are
          government requirements in Maharashtra and their charges are separate from platform fees.
        </p>
      </div>
    </div>
  );
}

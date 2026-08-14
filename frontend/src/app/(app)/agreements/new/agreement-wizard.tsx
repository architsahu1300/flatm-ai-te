"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Spinner } from "@/components/ui/spinner";
import { Textarea } from "@/components/ui/textarea";
import { ApiError } from "@/lib/api";
import {
  createAgreement,
  fetchStandardClauses,
  type Clause,
} from "@/lib/agreements-client";
import { cn } from "@/lib/utils";

const STEPS = ["Parties", "Property", "Terms", "Clauses", "Review"] as const;

export function AgreementWizard() {
  const router = useRouter();
  const [step, setStep] = useState(1);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // parties
  const [tenantEmails, setTenantEmails] = useState<string[]>([]);
  const [tenantInput, setTenantInput] = useState("");
  // property
  const [propertyAddress, setPropertyAddress] = useState("");
  // terms
  const [rent, setRent] = useState("");
  const [deposit, setDeposit] = useState("");
  const [duration, setDuration] = useState("11");
  const [notice, setNotice] = useState("30");
  const [lockIn, setLockIn] = useState("6");
  const [escalation, setEscalation] = useState("5");
  const [startDate, setStartDate] = useState("");
  // clauses
  const [clauses, setClauses] = useState<Clause[]>([]);
  const [customTitle, setCustomTitle] = useState("");
  const [customBody, setCustomBody] = useState("");

  useEffect(() => {
    fetchStandardClauses().then(setClauses).catch(() => {});
  }, []);

  function addTenant() {
    const email = tenantInput.trim().toLowerCase();
    if (email && /.+@.+\..+/.test(email) && !tenantEmails.includes(email)) {
      setTenantEmails([...tenantEmails, email]);
      setTenantInput("");
      setError(null);
    }
  }

  function addCustomClause() {
    if (customTitle.trim().length < 3 || customBody.trim().length < 10) return;
    setClauses([
      ...clauses,
      { id: `custom-${Date.now()}`, title: customTitle.trim(), body: customBody.trim(), source: "custom" },
    ]);
    setCustomTitle("");
    setCustomBody("");
  }

  async function submit() {
    setBusy(true);
    setError(null);
    try {
      const created = await createAgreement({
        tenantEmails,
        rentMonthly: Number(rent),
        deposit: Number(deposit || 0),
        durationMonths: Number(duration),
        noticePeriodDays: Number(notice),
        lockInMonths: Number(lockIn),
        annualEscalationPct: Number(escalation || 0),
        startDate,
        propertyAddress: propertyAddress || null,
        clauses,
      });
      router.push(`/agreements/${created.id}`);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Could not create the agreement");
      setBusy(false);
      if (e instanceof ApiError && e.code === "tenant_not_found") setStep(1);
    }
  }

  const canNext =
    step === 1 ? tenantEmails.length > 0
    : step === 2 ? true
    : step === 3 ? Number(rent) >= 1000 && !!startDate
    : true;

  return (
    <div className="mx-auto max-w-2xl">
      <h1 className="text-xl font-semibold tracking-tight">New rental agreement</h1>
      <p className="mt-1 text-sm text-text-muted">
        Maharashtra leave &amp; license · You are the landlord/licensor on this agreement.
      </p>

      <ol className="my-6 flex items-center gap-1">
        {STEPS.map((label, i) => (
          <li key={label} className="flex flex-1 flex-col items-center gap-1">
            <span className={cn("h-1.5 w-full rounded-chip", i + 1 <= step ? "bg-brand" : "bg-surface-2")} />
            <span className={cn("hidden text-[11px] sm:block", i + 1 === step ? "font-medium text-brand" : "text-text-muted")}>
              {label}
            </span>
          </li>
        ))}
      </ol>

      <div className="rounded-card border border-border bg-surface p-6 shadow-card">
        {step === 1 && (
          <div className="space-y-4">
            <div className="space-y-1.5">
              <Label htmlFor="tenant">Tenant email</Label>
              <p className="text-xs text-text-muted">
                Tenants need a {"Flatm'AI'te"} account — they&apos;ll review and e-sign from their side.
              </p>
              <div className="flex gap-2">
                <Input
                  id="tenant"
                  type="email"
                  placeholder="tenant@example.com"
                  value={tenantInput}
                  onChange={(e) => setTenantInput(e.target.value)}
                  onKeyDown={(e) => e.key === "Enter" && (e.preventDefault(), addTenant())}
                />
                <Button variant="outline" onClick={addTenant}>Add</Button>
              </div>
            </div>
            {tenantEmails.length > 0 && (
              <div className="flex flex-wrap gap-1.5">
                {tenantEmails.map((email) => (
                  <span key={email} className="flex items-center gap-1.5 rounded-chip bg-surface-2 px-2.5 py-1 text-sm">
                    {email}
                    <button
                      type="button"
                      onClick={() => setTenantEmails(tenantEmails.filter((t) => t !== email))}
                      className="cursor-pointer text-text-muted hover:text-danger"
                      aria-label={`Remove ${email}`}
                    >
                      ✕
                    </button>
                  </span>
                ))}
              </div>
            )}
          </div>
        )}

        {step === 2 && (
          <div className="space-y-1.5">
            <Label htmlFor="address">Property address</Label>
            <p className="text-xs text-text-muted">
              The full address as it should appear on the agreement.
            </p>
            <Textarea
              id="address"
              rows={3}
              placeholder="Flat 402, Sea Breeze CHS, Hill Road, Bandra West, Mumbai 400050"
              value={propertyAddress}
              onChange={(e) => setPropertyAddress(e.target.value)}
            />
          </div>
        )}

        {step === 3 && (
          <div className="grid grid-cols-2 gap-4">
            <div className="space-y-1.5">
              <Label htmlFor="rent">Monthly rent (₹)</Label>
              <Input id="rent" type="number" value={rent} onChange={(e) => setRent(e.target.value)} />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="deposit">Security deposit (₹)</Label>
              <Input id="deposit" type="number" value={deposit} onChange={(e) => setDeposit(e.target.value)} />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="duration">Term (months)</Label>
              <Input id="duration" type="number" value={duration} onChange={(e) => setDuration(e.target.value)} />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="start">Start date</Label>
              <Input id="start" type="date" value={startDate} onChange={(e) => setStartDate(e.target.value)} />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="lockin">Lock-in (months)</Label>
              <Input id="lockin" type="number" value={lockIn} onChange={(e) => setLockIn(e.target.value)} />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="notice">Notice period (days)</Label>
              <Input id="notice" type="number" value={notice} onChange={(e) => setNotice(e.target.value)} />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="esc">Annual escalation (%)</Label>
              <Input id="esc" type="number" step="0.5" value={escalation} onChange={(e) => setEscalation(e.target.value)} />
            </div>
          </div>
        )}

        {step === 4 && (
          <div className="space-y-4">
            <p className="text-sm text-text-muted">
              Standard Maharashtra clauses are included. Remove any, or add your own — AI
              suggestions become available on the agreement page after creation.
            </p>
            <ul className="space-y-2">
              {clauses.map((c) => (
                <li key={c.id} className="rounded-control border border-border p-3">
                  <div className="flex items-start justify-between gap-2">
                    <p className="text-sm font-medium">
                      {c.title}
                      {c.source !== "standard" && (
                        <span className="ml-2 rounded-chip bg-brand-soft px-1.5 py-0.5 text-[10px] text-brand">
                          {c.source === "ai" ? "AI draft" : "custom"}
                        </span>
                      )}
                    </p>
                    <button
                      type="button"
                      onClick={() => setClauses(clauses.filter((x) => x.id !== c.id))}
                      className="cursor-pointer text-xs text-text-muted hover:text-danger"
                    >
                      Remove
                    </button>
                  </div>
                  <p className="mt-1 line-clamp-2 text-xs text-text-muted">{c.body}</p>
                </li>
              ))}
            </ul>
            <div className="rounded-control bg-surface-2 p-3">
              <p className="mb-2 text-xs font-medium uppercase tracking-wide text-text-muted">Add a custom clause</p>
              <Input placeholder="Clause title" value={customTitle} onChange={(e) => setCustomTitle(e.target.value)} />
              <Textarea
                className="mt-2 bg-surface"
                rows={2}
                placeholder="Clause text…"
                value={customBody}
                onChange={(e) => setCustomBody(e.target.value)}
              />
              <Button variant="outline" size="sm" className="mt-2" onClick={addCustomClause}>
                Add clause
              </Button>
            </div>
          </div>
        )}

        {step === 5 && (
          <div className="space-y-4">
            <dl className="space-y-2 text-sm">
              {(
                [
                  ["Tenants", tenantEmails.join(", ")],
                  ["Property", propertyAddress || "—"],
                  ["Rent", `₹${Number(rent || 0).toLocaleString("en-IN")}/mo`],
                  ["Deposit", `₹${Number(deposit || 0).toLocaleString("en-IN")}`],
                  ["Term", `${duration} months from ${startDate || "—"}`],
                  ["Lock-in / notice", `${lockIn} months / ${notice} days`],
                  ["Escalation", `${escalation}% yearly`],
                  ["Clauses", `${clauses.length}`],
                ] as const
              ).map(([k, v]) => (
                <div key={k} className="flex justify-between gap-4 border-b border-border pb-2">
                  <dt className="shrink-0 text-text-muted">{k}</dt>
                  <dd className="min-w-0 truncate text-right font-medium">{v}</dd>
                </div>
              ))}
            </dl>
            <p className="rounded-control bg-warning-soft p-3 text-xs leading-relaxed text-warning">
              ⚠ AI-assisted documents are not legal advice. Stamp duty and registration are
              government charges, separate from platform fees, and required under Maharashtra law.
            </p>
          </div>
        )}

        {error && <p className="mt-4 text-sm text-danger">{error}</p>}

        <div className="mt-6 flex justify-between">
          <Button variant="ghost" disabled={step === 1} onClick={() => setStep(step - 1)}>
            Back
          </Button>
          {step < STEPS.length ? (
            <Button disabled={!canNext} onClick={() => setStep(step + 1)}>
              Continue
            </Button>
          ) : (
            <Button onClick={submit} disabled={busy || tenantEmails.length === 0 || !rent || !startDate}>
              {busy ? <Spinner /> : "Create draft"}
            </Button>
          )}
        </div>
      </div>
    </div>
  );
}

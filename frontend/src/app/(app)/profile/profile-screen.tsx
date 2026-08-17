"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { ThemeToggle } from "@/components/layout/ThemeToggle";
import { Avatar } from "@/components/ui/avatar";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { ChipSelect } from "@/components/ui/chip-select";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { Textarea } from "@/components/ui/textarea";
import { getSession, type SessionUser } from "@/lib/auth-client";
import type { Locality } from "@/lib/domain";
import {
  getMyFlatmateProfile,
  setFlatmateActive,
  upsertMyFlatmateProfile,
} from "@/lib/flatmates-client";
import { getLocalities } from "@/lib/profile-client";
import {
  fetchMyVerifications,
  requestVerification,
  type Verification,
  type VerificationType,
} from "@/lib/safety-client";

const VERIFICATION_ROWS: { type: VerificationType; label: string; hint: string; icon: string }[] = [
  { type: "EMAIL", label: "Email", hint: "Confirms you own your email address.", icon: "✉️" },
  { type: "PHONE", label: "Phone", hint: "Confirms your number via OTP.", icon: "📱" },
  { type: "GOV_ID", label: "Government ID", hint: "Reviewed by our team — unlocks the ✓ ID badge.", icon: "🪪" },
  { type: "SELFIE", label: "Selfie check", hint: "Matches your face to your ID.", icon: "🤳" },
];

interface MyFlatmateProfile {
  exists?: boolean;
  id?: string;
  headline?: string;
  about?: string;
  hasFlat?: boolean;
  budgetMin?: number | null;
  budgetMax?: number | null;
  localityIds?: string[];
  active?: boolean;
}

export function ProfileScreen() {
  const [user, setUser] = useState<SessionUser | null>(null);
  const [loaded, setLoaded] = useState(false);
  const [localities, setLocalities] = useState<Locality[]>([]);

  const [fm, setFm] = useState<MyFlatmateProfile>({});
  const [headline, setHeadline] = useState("");
  const [about, setAbout] = useState("");
  const [hasFlat, setHasFlat] = useState(false);
  const [budgetMin, setBudgetMin] = useState("");
  const [budgetMax, setBudgetMax] = useState("");
  const [selectedLocalities, setSelectedLocalities] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  useEffect(() => {
    getSession()
      .then((s) => setUser(s.user ?? null))
      .catch(() => {});
    getLocalities().then(setLocalities).catch(() => {});
    getMyFlatmateProfile()
      .then((data) => {
        const profile = data as MyFlatmateProfile;
        setFm(profile);
        if (profile.headline) setHeadline(profile.headline);
        if (profile.about) setAbout(profile.about);
        if (profile.hasFlat != null) setHasFlat(profile.hasFlat);
        if (profile.budgetMin) setBudgetMin(String(profile.budgetMin));
        if (profile.budgetMax) setBudgetMax(String(profile.budgetMax));
        if (profile.localityIds) setSelectedLocalities(profile.localityIds);
      })
      .catch(() => {})
      .finally(() => setLoaded(true));
  }, []);

  async function saveCard() {
    setBusy(true);
    setMessage(null);
    try {
      const body: Record<string, unknown> = { headline, about, hasFlat };
      if (budgetMin) body.budgetMin = Number(budgetMin);
      if (budgetMax) body.budgetMax = Number(budgetMax);
      if (selectedLocalities.length) body.localityIds = selectedLocalities;
      const saved = (await upsertMyFlatmateProfile(body)) as MyFlatmateProfile;
      setFm(saved);
      setMessage("Saved ✓");
    } catch (e) {
      setMessage(e instanceof Error ? e.message : "Save failed");
    } finally {
      setBusy(false);
    }
  }

  async function toggleActive() {
    setBusy(true);
    setMessage(null);
    try {
      const updated = (await setFlatmateActive(!fm.active)) as MyFlatmateProfile;
      setFm(updated);
      setMessage(updated.active ? "Your card is live in Find a Flatmate" : "Card hidden");
    } catch (e) {
      setMessage(e instanceof Error ? e.message : "Action failed");
    } finally {
      setBusy(false);
    }
  }

  if (!loaded) {
    return (
      <div className="mx-auto max-w-2xl space-y-4">
        <Skeleton className="h-24 rounded-card" />
        <Skeleton className="h-64 rounded-card" />
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-2xl space-y-6">
      <section className="rounded-card border border-border bg-surface p-6 shadow-card">
        <div className="flex items-center gap-4">
          <Avatar name={user?.name ?? "You"} size={56} />
          <div className="min-w-0 flex-1">
            <h1 className="text-xl font-semibold tracking-tight">{user?.name}</h1>
            <p className="text-sm text-text-muted">{user?.email ?? user?.phone}</p>
          </div>
          <Link href="/onboarding?step=3">
            <Button variant="outline" size="sm">Edit lifestyle</Button>
          </Link>
        </div>
        <div className="mt-5 flex items-center justify-between border-t border-border pt-4">
          <div>
            <p className="text-sm font-medium">Appearance</p>
            <p className="text-xs text-text-muted">Light, dark, or follow your device.</p>
          </div>
          <ThemeToggle />
        </div>
      </section>

      <section className="rounded-card border border-border bg-surface p-6 shadow-card">
        <div className="flex items-center justify-between">
          <div>
            <h2 className="font-semibold">Your flatmate card</h2>
            <p className="text-sm text-text-muted">
              This is how you appear to others when they&apos;re searching for flatmates.
            </p>
          </div>
          {fm.id && (
            <Badge variant={fm.active ? "success" : "outline"}>{fm.active ? "Live" : "Hidden"}</Badge>
          )}
        </div>

        <div className="mt-5 space-y-4">
          <div className="space-y-1.5">
            <Label htmlFor="headline">Headline</Label>
            <Input
              id="headline"
              placeholder="Quiet PM in BKC looking for a 2BHK share in Bandra"
              value={headline}
              onChange={(e) => setHeadline(e.target.value)}
            />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="about">About you as a flatmate</Label>
            <Textarea
              id="about"
              rows={3}
              placeholder="Routine, vibe, what you're looking for in a home…"
              value={about}
              onChange={(e) => setAbout(e.target.value)}
            />
          </div>
          <div className="space-y-1.5">
            <Label>Your situation</Label>
            <ChipSelect
              options={[
                { value: "false", label: "Looking for a place" },
                { value: "true", label: "Have a flat, need a flatmate" },
              ]}
              value={String(hasFlat)}
              onChange={(v) => setHasFlat(v === "true")}
            />
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div className="space-y-1.5">
              <Label htmlFor="fbmin">Budget min (₹/mo)</Label>
              <Input id="fbmin" type="number" value={budgetMin} onChange={(e) => setBudgetMin(e.target.value)} />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="fbmax">Budget max (₹/mo)</Label>
              <Input id="fbmax" type="number" value={budgetMax} onChange={(e) => setBudgetMax(e.target.value)} />
            </div>
          </div>
          <div className="space-y-1.5">
            <Label>Preferred areas</Label>
            <ChipSelect
              multi
              options={localities.map((l) => ({ value: l.id, label: l.name }))}
              value={selectedLocalities}
              onChange={(id) =>
                setSelectedLocalities((prev) =>
                  prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id],
                )
              }
            />
          </div>

          {message && <p className="text-sm text-text-muted">{message}</p>}

          <div className="flex gap-3">
            <Button onClick={saveCard} disabled={busy || headline.length < 10}>
              {busy ? <Spinner /> : "Save card"}
            </Button>
            {fm.id && (
              <Button variant="outline" onClick={toggleActive} disabled={busy}>
                {fm.active ? "Hide from discovery" : "Go live"}
              </Button>
            )}
          </div>
        </div>
      </section>

      <VerificationSection />
    </div>
  );
}

function VerificationSection() {
  const [verifications, setVerifications] = useState<Verification[] | null>(null);
  const [pendingType, setPendingType] = useState<VerificationType | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetchMyVerifications().then(setVerifications).catch(() => setVerifications([]));
  }, []);

  async function request(type: VerificationType) {
    setPendingType(type);
    setError(null);
    try {
      await requestVerification(type);
      setVerifications(await fetchMyVerifications());
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not start verification");
    } finally {
      setPendingType(null);
    }
  }

  const statusOf = (type: VerificationType) =>
    verifications?.find((v) => v.type === type)?.status ?? "UNVERIFIED";

  return (
    <section className="rounded-card border border-border bg-surface p-6 shadow-card">
      <h2 className="font-semibold">Verifications</h2>
      <p className="text-sm text-text-muted">
        Verified members get more replies — badges show on your listings and flatmate card.
      </p>

      <div className="mt-4 space-y-2.5">
        {verifications === null ? (
          <Skeleton className="h-32 rounded-card" />
        ) : (
          VERIFICATION_ROWS.map(({ type, label, hint, icon }) => {
            const status = statusOf(type);
            return (
              <div
                key={type}
                className="flex items-center gap-3 rounded-control bg-surface-2 px-3.5 py-3"
              >
                <span aria-hidden className="text-lg">{icon}</span>
                <div className="min-w-0 flex-1">
                  <p className="text-sm font-medium">{label}</p>
                  <p className="text-xs text-text-muted">{hint}</p>
                </div>
                {status === "VERIFIED" ? (
                  <Badge variant="success">✓ Verified</Badge>
                ) : status === "PENDING" ? (
                  <Badge variant="warning">Under review</Badge>
                ) : status === "REJECTED" ? (
                  <Badge variant="outline">Rejected</Badge>
                ) : (
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={pendingType !== null}
                    onClick={() => request(type)}
                  >
                    {pendingType === type ? <Spinner /> : "Verify"}
                  </Button>
                )}
              </div>
            );
          })
        )}
      </div>
      {error && <p className="mt-3 text-sm text-danger">{error}</p>}
    </section>
  );
}

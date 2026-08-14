"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { parseAsInteger, useQueryState } from "nuqs";
import { Button } from "@/components/ui/button";
import { ChipSelect, toOptions } from "@/components/ui/chip-select";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Spinner } from "@/components/ui/spinner";
import { LABELS, type Locality } from "@/lib/domain";
import { getLocalities, updatePreferences, updateProfile } from "@/lib/profile-client";

type Role = "find_room" | "find_flatmate" | "find_together" | "list_property" | "browsing";

const ROLES: { value: Role; title: string; desc: string; icon: string }[] = [
  { value: "find_room", title: "Find a room or flat", desc: "I'm looking for a place to live", icon: "🏠" },
  { value: "find_flatmate", title: "Find a flatmate", desc: "I have a place and need someone in it", icon: "🤝" },
  { value: "find_together", title: "Find a place with someone", desc: "Team up and search together", icon: "👥" },
  { value: "list_property", title: "List my property", desc: "I'm a landlord or owner", icon: "🔑" },
  { value: "browsing", title: "Just browsing", desc: "Show me around first", icon: "👀" },
];

const TOTAL_STEPS = 4;

export function OnboardingWizard() {
  const router = useRouter();
  const [step, setStep] = useQueryState("step", parseAsInteger.withDefault(1));
  const [role, setRole] = useState<Role | null>(null);
  const [busy, setBusy] = useState(false);
  const [localities, setLocalities] = useState<Locality[]>([]);

  // step 2 — basics
  const [dateOfBirth, setDateOfBirth] = useState("");
  const [gender, setGender] = useState<string | null>(null);
  const [occupation, setOccupation] = useState<string | null>(null);

  // step 3 — lifestyle
  const [cleanliness, setCleanliness] = useState<string | null>(null);
  const [socialStyle, setSocialStyle] = useState<string | null>(null);
  const [sleepSchedule, setSleepSchedule] = useState<string | null>(null);
  const [smoking, setSmoking] = useState<string | null>(null);
  const [drinking, setDrinking] = useState<string | null>(null);
  const [diet, setDiet] = useState<string | null>(null);
  const [pets, setPets] = useState<string | null>(null);
  const [wfh, setWfh] = useState<string | null>(null);

  // step 4 — rental prefs
  const [budgetMin, setBudgetMin] = useState("");
  const [budgetMax, setBudgetMax] = useState("");
  const [selectedLocalities, setSelectedLocalities] = useState<string[]>([]);
  const [moveInFrom, setMoveInFrom] = useState("");
  const [roomType, setRoomType] = useState<string | null>(null);

  useEffect(() => {
    getLocalities().then(setLocalities).catch(() => {});
  }, []);

  const skipTarget = role === "list_property" ? "/my-listings/new" : "/search";

  async function saveBasicsAndLifestyle() {
    const body: Record<string, unknown> = {};
    if (dateOfBirth) body.dateOfBirth = dateOfBirth;
    if (gender) body.gender = gender;
    if (occupation) body.occupation = occupation;
    if (cleanliness) body.cleanliness = cleanliness;
    if (socialStyle) body.socialStyle = socialStyle;
    if (sleepSchedule) body.sleepSchedule = sleepSchedule;
    if (smoking) body.smoking = smoking;
    if (drinking) body.drinking = drinking;
    if (diet) body.diet = diet;
    if (pets) body.pets = pets;
    if (wfh) body.wfhFrequency = wfh;
    if (Object.keys(body).length > 0) {
      await updateProfile(body);
    }
  }

  async function finish(withPrefs: boolean) {
    setBusy(true);
    try {
      await saveBasicsAndLifestyle();
      if (withPrefs) {
        const prefs: Record<string, unknown> = {};
        if (budgetMin) prefs.budgetMin = Number(budgetMin);
        if (budgetMax) prefs.budgetMax = Number(budgetMax);
        if (selectedLocalities.length) prefs.localityIds = selectedLocalities;
        if (moveInFrom) prefs.moveInFrom = moveInFrom;
        if (roomType) prefs.roomType = roomType;
        if (Object.keys(prefs).length > 0) {
          await updatePreferences(prefs);
        }
      }
      router.push(skipTarget);
      router.refresh();
    } catch {
      setBusy(false);
    }
  }

  return (
    <div>
      {/* progress */}
      <div className="mb-6 flex items-center justify-between">
        <div className="flex gap-1.5" aria-label={`Step ${step} of ${TOTAL_STEPS}`}>
          {Array.from({ length: TOTAL_STEPS }, (_, i) => (
            <span
              key={i}
              className={`h-1.5 w-8 rounded-chip ${i < step ? "bg-brand" : "bg-surface-2"}`}
            />
          ))}
        </div>
        <Link href={skipTarget} className="text-sm text-text-muted hover:text-text">
          Skip for now
        </Link>
      </div>

      {step === 1 && (
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">What brings you here?</h1>
          <p className="mt-1 text-sm text-text-muted">We&apos;ll tailor everything to this.</p>
          <div className="mt-6 space-y-2.5">
            {ROLES.map((r) => (
              <button
                key={r.value}
                type="button"
                onClick={() => {
                  setRole(r.value);
                  setStep(2);
                }}
                className="flex w-full items-center gap-4 rounded-card border border-border bg-surface p-4 text-left transition-all hover:border-brand hover:shadow-card cursor-pointer"
              >
                <span className="text-2xl">{r.icon}</span>
                <span>
                  <span className="block font-medium">{r.title}</span>
                  <span className="block text-sm text-text-muted">{r.desc}</span>
                </span>
              </button>
            ))}
          </div>
        </div>
      )}

      {step === 2 && (
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">The basics</h1>
          <p className="mt-1 text-sm text-text-muted">Helps people know who they&apos;d live with.</p>
          <div className="mt-6 space-y-5">
            <div className="space-y-1.5">
              <Label htmlFor="dob">Date of birth</Label>
              <Input id="dob" type="date" value={dateOfBirth} onChange={(e) => setDateOfBirth(e.target.value)} />
            </div>
            <div className="space-y-1.5">
              <Label>Gender</Label>
              <ChipSelect options={toOptions(LABELS.gender)} value={gender} onChange={setGender} />
            </div>
            <div className="space-y-1.5">
              <Label>Occupation</Label>
              <ChipSelect options={toOptions(LABELS.occupation)} value={occupation} onChange={setOccupation} />
            </div>
          </div>
          <div className="mt-8 flex justify-between">
            <Button variant="ghost" onClick={() => setStep(1)}>Back</Button>
            <Button onClick={() => setStep(3)}>Continue</Button>
          </div>
        </div>
      )}

      {step === 3 && (
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">Your lifestyle</h1>
          <p className="mt-1 text-sm text-text-muted">
            This powers your compatibility scores — the more you share, the better the matches.
          </p>
          <div className="mt-6 space-y-5">
            <div className="space-y-1.5">
              <Label>🧼 Cleanliness</Label>
              <ChipSelect options={toOptions(LABELS.cleanliness)} value={cleanliness} onChange={setCleanliness} />
            </div>
            <div className="space-y-1.5">
              <Label>☕ Social energy</Label>
              <ChipSelect options={toOptions(LABELS.social)} value={socialStyle} onChange={setSocialStyle} />
            </div>
            <div className="space-y-1.5">
              <Label>⏰ Schedule</Label>
              <ChipSelect options={toOptions(LABELS.sleep)} value={sleepSchedule} onChange={setSleepSchedule} />
            </div>
            <div className="space-y-1.5">
              <Label>🚭 Smoking</Label>
              <ChipSelect options={toOptions(LABELS.smoking)} value={smoking} onChange={setSmoking} />
            </div>
            <div className="space-y-1.5">
              <Label>🍷 Drinking</Label>
              <ChipSelect options={toOptions(LABELS.drinking)} value={drinking} onChange={setDrinking} />
            </div>
            <div className="space-y-1.5">
              <Label>🥗 Food</Label>
              <ChipSelect options={toOptions(LABELS.diet)} value={diet} onChange={setDiet} />
            </div>
            <div className="space-y-1.5">
              <Label>🐾 Pets</Label>
              <ChipSelect options={toOptions(LABELS.pets)} value={pets} onChange={setPets} />
            </div>
            <div className="space-y-1.5">
              <Label>💻 Work from home?</Label>
              <ChipSelect options={toOptions(LABELS.wfh)} value={wfh} onChange={setWfh} />
            </div>
          </div>
          <div className="mt-8 flex justify-between">
            <Button variant="ghost" onClick={() => setStep(2)}>Back</Button>
            {role === "list_property" || role === "browsing" ? (
              <Button onClick={() => finish(false)} disabled={busy}>
                {busy ? <Spinner /> : "Finish"}
              </Button>
            ) : (
              <Button onClick={() => setStep(4)}>Continue</Button>
            )}
          </div>
        </div>
      )}

      {step === 4 && (
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">What are you looking for?</h1>
          <p className="mt-1 text-sm text-text-muted">Rough is fine — you can refine anytime.</p>
          <div className="mt-6 space-y-5">
            <div className="grid grid-cols-2 gap-3">
              <div className="space-y-1.5">
                <Label htmlFor="bmin">Budget min (₹/mo)</Label>
                <Input id="bmin" type="number" inputMode="numeric" placeholder="10000" value={budgetMin} onChange={(e) => setBudgetMin(e.target.value)} />
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="bmax">Budget max (₹/mo)</Label>
                <Input id="bmax" type="number" inputMode="numeric" placeholder="25000" value={budgetMax} onChange={(e) => setBudgetMax(e.target.value)} />
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
            <div className="space-y-1.5">
              <Label htmlFor="movein">Move-in from</Label>
              <Input id="movein" type="date" value={moveInFrom} onChange={(e) => setMoveInFrom(e.target.value)} />
            </div>
            <div className="space-y-1.5">
              <Label>Room type</Label>
              <ChipSelect options={toOptions(LABELS.roomType)} value={roomType} onChange={setRoomType} />
            </div>
          </div>
          <div className="mt-8 flex justify-between">
            <Button variant="ghost" onClick={() => setStep(3)}>Back</Button>
            <Button onClick={() => finish(true)} disabled={busy} size="lg">
              {busy ? <Spinner /> : "Find my match"}
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}

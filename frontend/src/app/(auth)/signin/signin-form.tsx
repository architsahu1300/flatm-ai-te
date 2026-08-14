"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Spinner } from "@/components/ui/spinner";
import { ApiError } from "@/lib/api";
import { getProviders, login, requestOtp, verifyOtp } from "@/lib/auth-client";

type Mode = "email" | "phone";

export function SignInForm() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const nextPath = searchParams.get("next") ?? "/search";

  const [mode, setMode] = useState<Mode>("email");
  const [googleEnabled, setGoogleEnabled] = useState(false);
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [phone, setPhone] = useState("");
  const [otp, setOtp] = useState("");
  const [otpSent, setOtpSent] = useState(false);
  const [otpNote, setOtpNote] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    getProviders()
      .then((p) => setGoogleEnabled(p.google))
      .catch(() => {});
  }, []);

  async function submitEmail(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const user = await login({ email, password });
      router.push(user.onboarded ? nextPath : "/onboarding");
      router.refresh();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Something went wrong");
      setBusy(false);
    }
  }

  async function submitPhone(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      if (!otpSent) {
        const res = await requestOtp(phone);
        setOtpSent(true);
        setOtpNote(res.note ?? null);
      } else {
        const user = await verifyOtp({ phone, otp });
        router.push(user.onboarded ? nextPath : "/onboarding");
        router.refresh();
        return;
      }
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Something went wrong");
    }
    setBusy(false);
  }

  return (
    <div>
      <h1 className="text-2xl font-semibold tracking-tight">Welcome back</h1>
      <p className="mt-1 text-sm text-text-muted">Sign in to continue your search.</p>

      <div className="mt-6 flex rounded-control bg-surface-2 p-1 text-sm font-medium">
        {(["email", "phone"] as const).map((m) => (
          <button
            key={m}
            type="button"
            onClick={() => {
              setMode(m);
              setError(null);
            }}
            className={`flex-1 rounded-[calc(var(--radius-control)-4px)] py-1.5 capitalize transition-colors ${
              mode === m ? "bg-surface text-text shadow-sm" : "text-text-muted"
            }`}
          >
            {m === "email" ? "Email" : "Phone OTP"}
          </button>
        ))}
      </div>

      {mode === "email" ? (
        <form onSubmit={submitEmail} className="mt-6 space-y-4">
          <div className="space-y-1.5">
            <Label htmlFor="email">Email</Label>
            <Input
              id="email"
              type="email"
              required
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="you@example.com"
              autoComplete="email"
            />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="password">Password</Label>
            <Input
              id="password"
              type="password"
              required
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="••••••••"
              autoComplete="current-password"
            />
          </div>
          {error && <p className="text-sm text-danger">{error}</p>}
          <Button type="submit" className="w-full" size="lg" disabled={busy}>
            {busy ? <Spinner /> : "Sign in"}
          </Button>
        </form>
      ) : (
        <form onSubmit={submitPhone} className="mt-6 space-y-4">
          <div className="space-y-1.5">
            <Label htmlFor="phone">Phone number</Label>
            <div className="flex gap-2">
              <span className="flex h-10 items-center rounded-control border border-border bg-surface-2 px-3 text-sm text-text-muted">
                +91
              </span>
              <Input
                id="phone"
                type="tel"
                required
                disabled={otpSent}
                value={phone}
                onChange={(e) => setPhone(e.target.value.replace(/\D/g, "").slice(0, 10))}
                placeholder="98765 43210"
                autoComplete="tel"
              />
            </div>
          </div>
          {otpSent && (
            <div className="space-y-1.5">
              <div className="flex items-center justify-between">
                <Label htmlFor="otp">6-digit code</Label>
                <button
                  type="button"
                  disabled={busy}
                  onClick={async () => {
                    setError(null);
                    try {
                      const res = await requestOtp(phone);
                      setOtpNote(res.note ?? "Code re-sent");
                    } catch (err) {
                      setError(err instanceof ApiError ? err.message : "Could not resend");
                    }
                  }}
                  className="cursor-pointer text-xs font-medium text-brand hover:underline disabled:opacity-50"
                >
                  Resend
                </button>
              </div>
              <Input
                id="otp"
                inputMode="numeric"
                required
                value={otp}
                onChange={(e) => setOtp(e.target.value.replace(/\D/g, "").slice(0, 6))}
                placeholder="123456"
                autoComplete="one-time-code"
              />
              {otpNote && <p className="text-xs text-text-muted">{otpNote}</p>}
            </div>
          )}
          {error && <p className="text-sm text-danger">{error}</p>}
          <Button type="submit" className="w-full" size="lg" disabled={busy}>
            {busy ? <Spinner /> : otpSent ? "Verify & sign in" : "Send code"}
          </Button>
        </form>
      )}

      {googleEnabled && (
        <>
          <div className="my-5 flex items-center gap-3 text-xs text-text-muted">
            <span className="h-px flex-1 bg-border" /> or <span className="h-px flex-1 bg-border" />
          </div>
          <Button variant="outline" className="w-full" size="lg" onClick={() => (window.location.href = "/api/oauth2/authorization/google")}>
            Continue with Google
          </Button>
        </>
      )}

      <p className="mt-6 text-center text-sm text-text-muted">
        New here?{" "}
        <Link href="/signup" className="font-medium text-brand hover:underline">
          Create an account
        </Link>
      </p>
    </div>
  );
}

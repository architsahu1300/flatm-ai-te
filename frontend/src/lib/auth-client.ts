import { apiFetch, apiPost } from "@/lib/api";

export interface SessionUser {
  id: string;
  name: string;
  email: string | null;
  phone: string | null;
  role: "USER" | "ADMIN";
  onboarded: boolean;
}

export function register(body: { name: string; email: string; password: string }) {
  return apiPost<SessionUser>("/api/v1/auth/register", body);
}

export function login(body: { email: string; password: string }) {
  return apiPost<SessionUser>("/api/v1/auth/login", body);
}

export function logout() {
  return apiPost<{ ok: boolean }>("/api/v1/auth/logout", {});
}

export function requestOtp(phone: string) {
  return apiPost<{ sent: boolean; note?: string }>("/api/v1/auth/otp/request", { phone });
}

export function verifyOtp(body: { phone: string; otp: string; name?: string }) {
  return apiPost<SessionUser>("/api/v1/auth/otp/verify", body);
}

export function getSession() {
  return apiFetch<{ authenticated: boolean; user?: SessionUser }>("/api/v1/auth/session");
}

export function getProviders() {
  return apiFetch<{ google: boolean; otp: boolean }>("/api/v1/auth/providers");
}

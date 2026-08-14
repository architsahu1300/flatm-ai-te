/**
 * API helpers. Client components call relative /api/* (proxied to Spring by next.config rewrites,
 * same-origin so the httpOnly session cookie flows). Server components call the backend directly
 * and must forward the incoming cookie.
 */

export const BACKEND_URL = process.env.BACKEND_URL ?? "http://localhost:8080";

export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
    public fieldErrors?: Record<string, string>,
  ) {
    super(message);
  }
}

type Envelope<T> = { data: T } | { error: { code: string; message: string; fieldErrors?: Record<string, string> } };

async function parse<T>(res: Response): Promise<T> {
  const body = (await res.json().catch(() => null)) as Envelope<T> | null;
  if (!res.ok || !body || "error" in body) {
    const err = body && "error" in body ? body.error : { code: "unknown", message: `Request failed (${res.status})` };
    throw new ApiError(res.status, err.code, err.message, err.fieldErrors);
  }
  return body.data;
}

/** Browser-side fetch — relative URL through the Next proxy. */
export async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, {
    ...init,
    headers: { "Content-Type": "application/json", ...init?.headers },
  });
  return parse<T>(res);
}

export function apiPost<T>(path: string, body: unknown): Promise<T> {
  return apiFetch<T>(path, { method: "POST", body: JSON.stringify(body) });
}

/** Server-side fetch — direct to Spring, forwarding the caller's cookies. */
export async function serverFetch<T>(
  path: string,
  cookieHeader: string,
  init?: RequestInit,
): Promise<T> {
  const res = await fetch(`${BACKEND_URL}${path}`, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      cookie: cookieHeader,
      ...init?.headers,
    },
    cache: "no-store",
  });
  return parse<T>(res);
}

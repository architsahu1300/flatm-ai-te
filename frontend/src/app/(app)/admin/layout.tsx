import { cookies } from "next/headers";
import { notFound } from "next/navigation";
import { serverFetch } from "@/lib/api";
import type { SessionUser } from "@/lib/auth-client";
import { AdminNav } from "./admin-nav";

export default async function AdminLayout({ children }: { children: React.ReactNode }) {
  const cookieHeader = (await cookies()).toString();
  let session: { authenticated: boolean; user?: SessionUser };
  try {
    session = await serverFetch("/api/v1/auth/session", cookieHeader);
  } catch {
    notFound();
  }
  // non-admins get a 404, not a 403 — no need to advertise the route exists
  if (session.user?.role !== "ADMIN") {
    notFound();
  }

  return (
    <div className="mx-auto max-w-6xl">
      <div className="flex flex-col gap-6 lg:flex-row">
        <AdminNav />
        <div className="min-w-0 flex-1">{children}</div>
      </div>
    </div>
  );
}

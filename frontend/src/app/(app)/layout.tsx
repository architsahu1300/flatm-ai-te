import { cookies } from "next/headers";
import { BottomTabBar } from "@/components/layout/BottomTabBar";
import { TopNav } from "@/components/layout/TopNav";
import { serverFetch } from "@/lib/api";
import type { SessionUser } from "@/lib/auth-client";

async function getSessionUser(): Promise<SessionUser | null> {
  const cookieStore = await cookies();
  const cookieHeader = cookieStore.toString();
  if (!cookieHeader.includes("fm_token")) {
    return null;
  }
  try {
    const session = await serverFetch<{ authenticated: boolean; user?: SessionUser }>(
      "/api/v1/auth/session",
      cookieHeader,
    );
    return session.authenticated && session.user ? session.user : null;
  } catch {
    return null;
  }
}

export default async function AppLayout({ children }: { children: React.ReactNode }) {
  const user = await getSessionUser();
  return (
    <div className="min-h-dvh bg-bg pb-20 md:pb-0">
      <TopNav user={user} />
      <main className="mx-auto max-w-7xl px-4 py-6 sm:px-6">{children}</main>
      <BottomTabBar />
    </div>
  );
}

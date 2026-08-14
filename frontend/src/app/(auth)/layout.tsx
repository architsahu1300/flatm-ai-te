import Link from "next/link";
import { Wordmark } from "@/lib/brand";

export default function AuthLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex min-h-dvh flex-col items-center justify-center bg-bg px-4 py-10">
      <Link href="/" className="mb-8 text-2xl font-bold tracking-tight text-text">
        <Wordmark />
      </Link>
      <div className="w-full max-w-md rounded-card border border-border bg-surface p-6 shadow-card sm:p-8">
        {children}
      </div>
    </div>
  );
}

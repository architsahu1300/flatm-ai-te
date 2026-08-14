import Link from "next/link";
import { HeroSearch } from "@/components/search/HeroSearch";
import { ThemeToggle } from "@/components/layout/ThemeToggle";
import { Wordmark } from "@/lib/brand";

const STEPS = [
  { n: 1, title: "Say what you need", desc: "In your own words — budget, area, vibe, people." },
  { n: 2, title: "We turn it into requirements", desc: "Editable chips — fix anything we got wrong." },
  { n: 3, title: "Ranked matches, honest reasons", desc: "Every match explains itself — including concerns." },
  { n: 4, title: "Chat safely in-app", desc: "Verified profiles, no phone numbers shared upfront." },
  { n: 5, title: "Close it with an agreement", desc: "Draft a rental agreement right here." },
];

const TRUST = [
  ["✦", "AI-ranked matches"],
  ["🛡", "Verified users"],
  ["₹0", "No brokerage"],
  ["📄", "Rental agreements"],
] as const;

const LOCALITIES = ["Andheri", "Bandra", "Powai", "Lower Parel", "Worli", "BKC", "Malad", "Ghatkopar"];

export default function LandingPage() {
  return (
    <div className="min-h-dvh bg-bg">
      {/* Header */}
      <header className="sticky top-0 z-40 border-b border-border bg-surface/80 backdrop-blur">
        <div className="mx-auto flex h-16 max-w-6xl items-center justify-between px-5">
          <Link href="/" className="text-lg font-bold tracking-tight">
            <Wordmark />
          </Link>
          <nav className="hidden items-center gap-6 text-sm font-medium text-text-muted md:flex">
            <Link href="/explore" className="hover:text-text">Browse homes</Link>
            <Link href="/flatmates" className="hover:text-text">Find flatmates</Link>
          </nav>
          <div className="flex items-center gap-3">
            <ThemeToggle compact />
            <Link href="/signin" className="text-sm font-medium text-text-muted hover:text-text">
              Sign in
            </Link>
            <Link
              href="/signup"
              className="rounded-control bg-brand px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-brand-hover"
            >
              Get started
            </Link>
          </div>
        </div>
      </header>

      {/* Hero */}
      <section className="relative overflow-hidden px-5 pb-16 pt-16 sm:pb-20 sm:pt-24">
        {/* Two soft ambient orbs, per the design (top-left brand, bottom-right neutral) */}
        <div aria-hidden className="pointer-events-none absolute inset-0 opacity-30">
          <div
            className="absolute -left-[10%] -top-[20%] h-[600px] w-[600px] rounded-full blur-[120px] sm:h-[800px] sm:w-[800px]"
            style={{ background: "var(--color-brand-soft)" }}
          />
          <div
            className="absolute -bottom-[10%] -right-[10%] h-[450px] w-[450px] rounded-full blur-[100px] sm:h-[600px] sm:w-[600px]"
            style={{ background: "var(--color-surface-2)" }}
          />
        </div>
        <div className="relative mx-auto max-w-3xl text-center">
          <h1 className="text-balance text-4xl font-bold leading-tight tracking-tight sm:text-6xl">
            Find your next home.
            <br />
            Find the right person to share it with.
          </h1>
          <p className="mx-auto mt-4 max-w-xl text-lg text-text-muted">
            Describe what you&apos;re looking for in plain words. Our AI finds, ranks and explains
            your best matches — no brokers, no spam.
          </p>
          <div className="mt-9">
            <HeroSearch />
          </div>
          <div className="mt-8 flex flex-col justify-center gap-3 sm:flex-row">
            <Link
              href="/search"
              className="rounded-control bg-brand px-6 py-3 text-sm font-semibold text-white transition-colors hover:bg-brand-hover"
            >
              Find my match
            </Link>
            <Link
              href="/explore"
              className="rounded-control border border-border bg-surface px-6 py-3 text-sm font-semibold transition-colors hover:bg-surface-2"
            >
              Browse homes
            </Link>
            <Link
              href="/flatmates"
              className="rounded-control border border-border bg-surface px-6 py-3 text-sm font-semibold transition-colors hover:bg-surface-2"
            >
              Find a flatmate
            </Link>
          </div>

          {/* Trust strip lives inside the hero, per the design */}
          <div className="mt-12 flex flex-wrap items-center justify-center gap-x-4 gap-y-3 border-t border-border pt-7 text-sm text-text-muted sm:mt-14 sm:gap-x-5">
            {TRUST.map(([icon, label], i) => (
              <span key={label} className="flex items-center gap-x-4 sm:gap-x-5">
                {i > 0 && <span aria-hidden className="hidden h-1 w-1 rounded-chip bg-border sm:block" />}
                <span className="flex items-center gap-2">
                  <span aria-hidden>{icon}</span> {label}
                </span>
              </span>
            ))}
          </div>
        </div>
      </section>

      {/* How it works */}
      <section className="border-t border-border bg-surface px-5 py-20 sm:py-24">
        <div className="mx-auto max-w-5xl">
          <h2 className="text-center text-2xl font-bold tracking-tight sm:text-3xl">How it works</h2>
          <ol className="mt-12 grid gap-8 sm:grid-cols-2 lg:grid-cols-5">
            {STEPS.map((step) => (
              <li key={step.n} className="relative">
                <span className="flex h-9 w-9 items-center justify-center rounded-chip bg-brand-soft text-sm font-bold text-brand">
                  {step.n}
                </span>
                <h3 className="mt-3 font-semibold">{step.title}</h3>
                <p className="mt-1 text-sm leading-relaxed text-text-muted">{step.desc}</p>
              </li>
            ))}
          </ol>
        </div>
      </section>

      {/* Two-sided split — immersive cards: ambient backdrop + dark scrim + light text */}
      <section className="px-5 py-20">
        <div className="mx-auto grid max-w-5xl gap-6 md:grid-cols-2">
          {(
            [
              {
                href: "/search",
                icon: "🔎",
                title: "Looking for a place?",
                desc: "Rooms, shares and whole flats — matched to your budget, commute and how you actually live.",
                cta: "Start searching",
                bg: "linear-gradient(140deg, #0e7490 0%, #155e75 45%, #0b1114 100%)",
                glow: "radial-gradient(closest-side, rgba(211,241,255,0.35), transparent)",
              },
              {
                href: "/my-listings/new",
                icon: "🔑",
                title: "Have a room to fill?",
                desc: "List free in five minutes. We surface compatible people — not a flood of random calls.",
                cta: "List your room",
                bg: "linear-gradient(140deg, #a16207 0%, #6d4c11 40%, #0b1114 100%)",
                glow: "radial-gradient(closest-side, rgba(254,243,199,0.3), transparent)",
              },
            ] as const
          ).map((card) => (
            <Link
              key={card.href}
              href={card.href}
              className="group relative overflow-hidden rounded-card border border-border shadow-card transition-shadow hover:shadow-pop"
            >
              <div
                aria-hidden
                className="absolute inset-0 transition-transform duration-700 group-hover:scale-105"
                style={{ background: card.bg }}
              >
                <div className="absolute -right-10 -top-16 h-56 w-56 rounded-chip blur-2xl" style={{ background: card.glow }} />
              </div>
              {/* scrim keeps text AA on any backdrop, both themes */}
              <div aria-hidden className="absolute inset-0 bg-gradient-to-t from-[#0B1114]/85 via-[#0B1114]/30 to-transparent" />
              <div className="relative flex min-h-64 flex-col justify-end p-8 text-white">
                <span className="mb-4 flex h-12 w-12 items-center justify-center rounded-control bg-white/20 text-2xl backdrop-blur-sm">
                  {card.icon}
                </span>
                <h3 className="text-xl font-semibold">{card.title}</h3>
                <p className="mt-1.5 text-sm leading-relaxed text-white/80">{card.desc}</p>
                <span className="mt-4 inline-flex items-center gap-2 text-sm font-medium transition-all group-hover:gap-3">
                  {card.cta} <span aria-hidden>→</span>
                </span>
              </div>
            </Link>
          ))}
        </div>
      </section>

      {/* Localities */}
      <section className="px-5 py-16">
        <div className="mx-auto max-w-4xl text-center">
          <h2 className="text-lg font-semibold">Popular in Mumbai</h2>
          <div className="mt-5 flex flex-wrap justify-center gap-2">
            {LOCALITIES.map((name) => (
              <Link
                key={name}
                href={`/search?q=${encodeURIComponent(`Room in ${name}`)}`}
                className="rounded-chip border border-border bg-surface px-4 py-1.5 text-sm text-text-muted transition-colors hover:border-brand hover:text-brand"
              >
                {name}
              </Link>
            ))}
          </div>
        </div>
      </section>

      {/* Footer */}
      <footer className="border-t border-border px-5 py-10">
        <div className="mx-auto flex max-w-5xl flex-col items-center justify-between gap-4 text-sm text-text-muted sm:flex-row">
          <p>
            <Wordmark /> — AI-first flatmate &amp; rental matching
          </p>
          <p>Made for Mumbai · Match scores are estimates, always verify in person</p>
        </div>
      </footer>
    </div>
  );
}

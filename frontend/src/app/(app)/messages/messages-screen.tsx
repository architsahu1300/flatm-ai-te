"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Avatar } from "@/components/ui/avatar";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { Textarea } from "@/components/ui/textarea";
import { formatINR, formatRelativeTime } from "@/lib/domain";
import {
  listConversations,
  startConversation,
  type ConversationSummary,
} from "@/lib/messaging-client";
import { cn } from "@/lib/utils";

export function MessagesScreen() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const to = searchParams.get("to");
  const listing = searchParams.get("listing");

  const [tab, setTab] = useState<"inbox" | "requests">("inbox");
  const [items, setItems] = useState<ConversationSummary[] | null>(null);
  const [firstMessage, setFirstMessage] = useState(
    "Hi! I saw your listing — is it still available?",
  );
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    listConversations().then(setItems).catch(() => setItems([]));
  }, []);

  useEffect(() => {
    load();
    const t = setInterval(load, 30_000);
    return () => clearInterval(t);
  }, [load]);

  async function startNew() {
    if (!to) return;
    setSending(true);
    setError(null);
    try {
      const created = await startConversation({
        recipientId: to,
        listingId: listing,
        firstMessage: firstMessage.trim(),
      });
      router.replace(`/messages/${created.id}`);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not send");
      setSending(false);
    }
  }

  const requests = (items ?? []).filter((c) => c.status === "PENDING" && !c.isInitiator);
  const inbox = (items ?? []).filter((c) => !(c.status === "PENDING" && !c.isInitiator));
  const shown = tab === "inbox" ? inbox : requests;

  return (
    <div className="mx-auto max-w-2xl">
      <h1 className="mb-4 text-xl font-semibold tracking-tight">Messages</h1>

      {/* New-conversation composer (arrived via a Message button) */}
      {to && (
        <div className="mb-6 rounded-card border border-brand bg-brand-soft/40 p-4">
          <p className="text-sm font-medium">Send a message request</p>
          <p className="mt-1 text-xs text-text-muted">
            They&apos;ll see your profile and can accept before you chat further.
          </p>
          <Textarea
            className="mt-3 bg-surface"
            rows={3}
            value={firstMessage}
            onChange={(e) => setFirstMessage(e.target.value)}
          />
          {error && <p className="mt-2 text-sm text-danger">{error}</p>}
          <div className="mt-3 flex justify-end gap-2">
            <Button variant="ghost" onClick={() => router.replace("/messages")}>Cancel</Button>
            <Button onClick={startNew} disabled={sending || firstMessage.trim().length < 2}>
              {sending ? <Spinner /> : "Send request"}
            </Button>
          </div>
        </div>
      )}

      <div className="mb-4 flex w-fit rounded-control bg-surface-2 p-1 text-sm font-medium">
        {(
          [
            ["inbox", `Inbox (${inbox.length})`],
            ["requests", `Requests (${requests.length})`],
          ] as const
        ).map(([t, label]) => (
          <button
            key={t}
            type="button"
            onClick={() => setTab(t)}
            className={cn(
              "cursor-pointer rounded-[calc(var(--radius-control)-4px)] px-4 py-1.5 transition-colors",
              tab === t ? "bg-surface shadow-sm" : "text-text-muted",
            )}
          >
            {label}
          </button>
        ))}
      </div>

      {items === null ? (
        <div className="space-y-2">
          {Array.from({ length: 4 }, (_, i) => (
            <Skeleton key={i} className="h-20 rounded-card" />
          ))}
        </div>
      ) : shown.length === 0 ? (
        <div className="rounded-card border border-border bg-surface p-10 text-center">
          <p className="font-medium">
            {tab === "inbox" ? "No conversations yet" : "No pending requests"}
          </p>
          <p className="mt-1 text-sm text-text-muted">
            {tab === "inbox"
              ? "Find a place or a person and hit Message to start."
              : "New message requests from other members will appear here."}
          </p>
        </div>
      ) : (
        <div className="space-y-2">
          {shown.map((c) => (
            <Link
              key={c.id}
              href={`/messages/${c.id}`}
              className="flex items-center gap-3 rounded-card border border-border bg-surface p-4 shadow-card transition-colors hover:bg-surface-2/50"
            >
              <Avatar name={c.otherUserName} src={c.otherUserImage} size={44} />
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-2">
                  <p className="truncate font-medium">{c.otherUserName}</p>
                  {c.status === "PENDING" && <Badge variant="warning">Request</Badge>}
                  {c.status === "BLOCKED" && <Badge variant="outline">Blocked</Badge>}
                  {c.status === "REJECTED" && <Badge variant="outline">Declined</Badge>}
                </div>
                {c.listingTitle && (
                  <p className="truncate text-xs text-brand">
                    🏠 {c.listingTitle}
                    {c.listingRent != null && (
                      <span className="tnum text-text-muted"> · {formatINR(c.listingRent)}/mo</span>
                    )}
                  </p>
                )}
                <p className="truncate text-sm text-text-muted">{c.lastPreview}</p>
              </div>
              <div className="flex shrink-0 flex-col items-end gap-1">
                {c.lastMessageAt && (
                  <span className="tnum text-xs text-text-muted">{formatRelativeTime(c.lastMessageAt)}</span>
                )}
                {c.unreadCount > 0 && (
                  <span className="tnum flex h-5 min-w-5 items-center justify-center rounded-chip bg-brand px-1.5 text-xs font-bold text-white">
                    {c.unreadCount}
                  </span>
                )}
              </div>
            </Link>
          ))}
          <p className="py-4 text-center text-xs text-text-muted">📭 No more messages</p>
        </div>
      )}
    </div>
  );
}

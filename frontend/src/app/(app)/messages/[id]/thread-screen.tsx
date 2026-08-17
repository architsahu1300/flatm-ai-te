"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { Avatar } from "@/components/ui/avatar";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { getSession } from "@/lib/auth-client";
import { APP_NAME } from "@/lib/brand";
import { ReportDialog } from "@/components/report/ReportDialog";
import {
  fetchMessages,
  listConversations,
  markRead,
  respondToRequest,
  sendMessage,
  type ChatMessage,
  type ConversationSummary,
} from "@/lib/messaging-client";
import { cn } from "@/lib/utils";

const PAYMENT_PATTERN = /\b(upi|gpay|phonepe|paytm|advance|transfer|deposit first|send money|₹\s*\d)/i;

export function ThreadScreen({ conversationId }: { conversationId: string }) {
  const router = useRouter();
  const [me, setMe] = useState<string | null>(null);
  const [conversation, setConversation] = useState<ConversationSummary | null>(null);
  const [messages, setMessages] = useState<ChatMessage[] | null>(null);
  const [draft, setDraft] = useState("");
  const [bannerDismissed, setBannerDismissed] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const bottomRef = useRef<HTMLDivElement>(null);

  const loadAll = useCallback(async () => {
    try {
      const [convos, msgs] = await Promise.all([
        listConversations(),
        fetchMessages(conversationId),
      ]);
      setConversation(convos.find((c) => c.id === conversationId) ?? null);
      setMessages(msgs);
    } catch {
      setMessages([]);
    }
  }, [conversationId]);

  useEffect(() => {
    getSession().then((s) => setMe(s.user?.id ?? null)).catch(() => {});
    loadAll();
    markRead(conversationId).catch(() => {});
    const t = setInterval(loadAll, 5000);
    return () => clearInterval(t);
  }, [conversationId, loadAll]);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ block: "end" });
  }, [messages?.length]);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    const body = draft.trim();
    if (!body) return;
    setDraft("");
    setError(null);
    // optimistic append
    const optimistic: ChatMessage = {
      id: `tmp-${Date.now()}`,
      senderId: me ?? "",
      body,
      readAt: null,
      createdAt: new Date().toISOString(),
    };
    setMessages((prev) => [...(prev ?? []), optimistic]);
    try {
      await sendMessage(conversationId, body);
      loadAll();
    } catch (err) {
      setMessages((prev) => (prev ?? []).filter((m) => m.id !== optimistic.id));
      setDraft(body);
      setError(err instanceof Error ? err.message : "Could not send");
    }
  }

  async function respond(action: "accept" | "reject" | "block") {
    try {
      await respondToRequest(conversationId, action);
      if (action === "block") {
        router.push("/messages");
        return;
      }
      loadAll();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Action failed");
    }
  }

  const isPendingForMe =
    conversation?.status === "PENDING" && !conversation.isInitiator;
  const isPendingSent = conversation?.status === "PENDING" && conversation.isInitiator;
  const closed = conversation?.status === "REJECTED" || conversation?.status === "BLOCKED";
  const paymentTalk = (messages ?? []).slice(-5).some((m) => PAYMENT_PATTERN.test(m.body));
  const showBanner = !bannerDismissed && ((messages ?? []).length <= 4 || paymentTalk);

  return (
    <div className="mx-auto flex h-[calc(100dvh-7rem)] max-w-2xl flex-col md:h-[calc(100dvh-9rem)]">
      {/* header */}
      <div className="flex items-center gap-3 border-b border-border pb-3">
        <Link href="/messages" className="text-text-muted hover:text-text" aria-label="Back to messages">
          ←
        </Link>
        {conversation ? (
          <>
            <Avatar name={conversation.otherUserName} src={conversation.otherUserImage} size={36} />
            <div className="min-w-0 flex-1">
              <p className="truncate font-medium">{conversation.otherUserName}</p>
              {conversation.listingTitle && (
                <Link
                  href={`/listing/${conversation.listingId}`}
                  className="block truncate text-xs text-brand hover:underline"
                >
                  re: {conversation.listingTitle}
                </Link>
              )}
            </div>
            {conversation.status === "ACCEPTED" && (
              <div className="flex items-center gap-3">
                <ReportDialog userId={conversation.otherUserId} subject={conversation.otherUserName} />
                <button
                  type="button"
                  onClick={() => respond("block")}
                  className="cursor-pointer text-xs text-text-muted hover:text-danger"
                >
                  Block
                </button>
              </div>
            )}
            {closed && <Badge variant="outline">{conversation.status === "BLOCKED" ? "Blocked" : "Declined"}</Badge>}
          </>
        ) : (
          <Skeleton className="h-9 w-48" />
        )}
      </div>

      {/* safety banner */}
      {showBanner && (
        <div className="mt-3 flex items-start gap-2 rounded-card bg-warning-soft p-3 text-[13px] leading-relaxed text-warning">
          <span aria-hidden>🛡</span>
          <p className="flex-1">
            Never share OTPs or transfer a deposit before verifying the property and person in real
            life. {APP_NAME} never asks for payments in chat.
          </p>
          <button
            type="button"
            onClick={() => setBannerDismissed(true)}
            className="cursor-pointer font-medium"
            aria-label="Dismiss safety notice"
          >
            ✕
          </button>
        </div>
      )}

      {/* messages */}
      <div className="flex-1 space-y-2 overflow-y-auto py-4">
        {messages === null ? (
          <div className="space-y-2">
            <Skeleton className="h-10 w-2/3 rounded-card" />
            <Skeleton className="ml-auto h-10 w-1/2 rounded-card" />
          </div>
        ) : (
          messages.map((m) => {
            const mine = m.senderId === me;
            return (
              <div
                key={m.id}
                className={cn(
                  "max-w-[80%] whitespace-pre-line rounded-card px-3.5 py-2 text-sm leading-relaxed",
                  mine ? "ml-auto bg-brand-soft" : "bg-surface-2",
                )}
              >
                {m.body}
                <span className="mt-0.5 block text-right text-[10px] text-text-muted">
                  {new Date(m.createdAt).toLocaleTimeString("en-IN", { hour: "2-digit", minute: "2-digit" })}
                  {mine && m.readAt && " · read"}
                </span>
              </div>
            );
          })
        )}
        <div ref={bottomRef} />
      </div>

      {/* request actions / composer */}
      {isPendingForMe ? (
        <div className="rounded-card border border-border bg-surface p-4">
          <p className="text-sm font-medium">Accept this message request?</p>
          <p className="mt-0.5 text-xs text-text-muted">
            They can only keep messaging you if you accept.
          </p>
          <div className="mt-3 flex gap-2">
            <Button className="flex-1" onClick={() => respond("accept")}>Accept</Button>
            <Button variant="outline" className="flex-1" onClick={() => respond("reject")}>Decline</Button>
            <Button variant="ghost" onClick={() => respond("block")}>Block</Button>
          </div>
        </div>
      ) : closed ? (
        <p className="rounded-card bg-surface-2 p-3 text-center text-sm text-text-muted">
          This conversation is closed.
        </p>
      ) : (
        <div>
          {isPendingSent && (
            <p className="mb-2 rounded-card bg-surface-2 p-2.5 text-center text-xs text-text-muted">
              Request sent — you can chat once they accept.
            </p>
          )}
          {error && <p className="mb-2 text-sm text-danger">{error}</p>}
          <form onSubmit={submit} className="flex gap-2">
            <input
              value={draft}
              onChange={(e) => setDraft(e.target.value)}
              disabled={isPendingSent}
              placeholder={isPendingSent ? "Waiting for them to accept…" : "Write a message…"}
              aria-label="Message"
              className="h-11 flex-1 rounded-chip border border-border bg-surface px-4 text-sm placeholder:text-text-muted focus:border-brand focus:outline-none disabled:opacity-50"
            />
            <Button type="submit" disabled={isPendingSent || draft.trim().length === 0}>
              Send
            </Button>
          </form>
        </div>
      )}
    </div>
  );
}

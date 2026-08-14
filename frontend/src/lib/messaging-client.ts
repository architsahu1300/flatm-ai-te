import { apiFetch, apiPost } from "@/lib/api";

export interface ConversationSummary {
  id: string;
  otherUserId: string;
  otherUserName: string;
  otherUserImage: string | null;
  status: "PENDING" | "ACCEPTED" | "REJECTED" | "BLOCKED";
  isInitiator: boolean;
  listingId: string | null;
  listingTitle: string | null;
  listingRent: number | null;
  lastPreview: string | null;
  lastMessageAt: string | null;
  unreadCount: number;
}

export interface ChatMessage {
  id: string;
  senderId: string;
  body: string;
  readAt: string | null;
  createdAt: string;
}

export function listConversations() {
  return apiFetch<ConversationSummary[]>("/api/v1/conversations");
}

export function startConversation(body: { recipientId: string; listingId?: string | null; firstMessage: string }) {
  return apiPost<{ id: string; status: string }>("/api/v1/conversations", body);
}

export function fetchMessages(conversationId: string, after?: string) {
  const qs = after ? `?after=${encodeURIComponent(after)}` : "";
  return apiFetch<ChatMessage[]>(`/api/v1/conversations/${conversationId}/messages${qs}`);
}

export function sendMessage(conversationId: string, body: string) {
  return apiPost<ChatMessage>(`/api/v1/conversations/${conversationId}/messages`, { body });
}

export function respondToRequest(conversationId: string, action: "accept" | "reject" | "block") {
  return apiPost<{ status: string }>(`/api/v1/conversations/${conversationId}/${action}`, {});
}

export function markRead(conversationId: string) {
  return apiPost<{ ok: boolean }>(`/api/v1/conversations/${conversationId}/read`, {});
}

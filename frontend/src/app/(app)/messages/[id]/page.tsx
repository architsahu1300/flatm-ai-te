import { ThreadScreen } from "./thread-screen";

export const metadata = { title: "Conversation" };

export default async function ThreadPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <ThreadScreen conversationId={id} />;
}

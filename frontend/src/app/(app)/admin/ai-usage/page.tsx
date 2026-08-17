import type { Metadata } from "next";
import { AiUsageScreen } from "./ai-usage-screen";

export const metadata: Metadata = { title: "Admin · AI usage" };

export default function AdminAiUsagePage() {
  return <AiUsageScreen />;
}

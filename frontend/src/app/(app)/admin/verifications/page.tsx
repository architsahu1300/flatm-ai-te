import type { Metadata } from "next";
import { VerificationsScreen } from "./verifications-screen";

export const metadata: Metadata = { title: "Admin · Verifications" };

export default function AdminVerificationsPage() {
  return <VerificationsScreen />;
}

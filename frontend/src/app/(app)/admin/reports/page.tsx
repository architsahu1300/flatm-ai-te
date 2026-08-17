import type { Metadata } from "next";
import { ReportsScreen } from "./reports-screen";

export const metadata: Metadata = { title: "Admin · Reports" };

export default function AdminReportsPage() {
  return <ReportsScreen />;
}

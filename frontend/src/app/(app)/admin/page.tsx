import type { Metadata } from "next";
import { OverviewScreen } from "./overview-screen";

export const metadata: Metadata = { title: "Admin · Overview" };

export default function AdminOverviewPage() {
  return <OverviewScreen />;
}

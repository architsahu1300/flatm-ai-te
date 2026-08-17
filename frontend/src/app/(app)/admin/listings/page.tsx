import type { Metadata } from "next";
import { ListingsScreen } from "./listings-screen";

export const metadata: Metadata = { title: "Admin · Listings" };

export default function AdminListingsPage() {
  return <ListingsScreen />;
}

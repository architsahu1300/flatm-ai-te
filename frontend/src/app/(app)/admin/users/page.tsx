import type { Metadata } from "next";
import { UsersScreen } from "./users-screen";

export const metadata: Metadata = { title: "Admin · Users" };

export default function AdminUsersPage() {
  return <UsersScreen />;
}

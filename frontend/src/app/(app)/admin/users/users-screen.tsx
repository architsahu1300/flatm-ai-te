"use client";

import { useEffect, useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { fetchUsers, setUserSuspended, type AdminUser } from "@/lib/admin-client";
import { EmptyRow, TableCard, Td, Th } from "../admin-bits";

export function UsersScreen() {
  const [query, setQuery] = useState("");
  const [users, setUsers] = useState<AdminUser[] | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const t = setTimeout(() => {
      fetchUsers(query).then(setUsers).catch(() => setUsers([]));
    }, query ? 300 : 0);
    return () => clearTimeout(t);
  }, [query]);

  async function toggleSuspend(user: AdminUser) {
    setBusyId(user.id);
    setError(null);
    try {
      await setUserSuspended(user.id, !user.is_suspended);
      setUsers((prev) =>
        (prev ?? []).map((u) => (u.id === user.id ? { ...u, is_suspended: !user.is_suspended } : u)),
      );
    } catch (e) {
      setError(e instanceof Error ? e.message : "Action failed");
    } finally {
      setBusyId(null);
    }
  }

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-semibold tracking-tight">Users</h1>
      {error && <p className="rounded-card bg-danger-soft p-3 text-sm text-danger">{error}</p>}
      <TableCard
        title={users ? `${users.length} shown` : "Loading…"}
        action={
          <Input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search name or email…"
            aria-label="Search users"
            className="h-9 w-56"
          />
        }
      >
        {users === null ? (
          <div className="p-4"><Skeleton className="h-48 rounded-card" /></div>
        ) : (
          <table className="w-full min-w-[720px]">
            <thead>
              <tr className="border-b border-border">
                <Th>User</Th>
                <Th>Role</Th>
                <Th className="text-right">Listings</Th>
                <Th className="text-right">Reports against</Th>
                <Th>Joined</Th>
                <Th>Status</Th>
                <Th />
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {users.length === 0 && <EmptyRow colSpan={7} text="No users match." />}
              {users.map((u) => (
                <tr key={u.id} className={u.is_suspended ? "opacity-60" : undefined}>
                  <Td>
                    <p className="font-medium">{u.name}</p>
                    <p className="text-xs text-text-muted">{u.email ?? u.phone ?? "—"}</p>
                  </Td>
                  <Td>{u.role === "ADMIN" ? <Badge variant="brand">Admin</Badge> : <span className="text-text-muted">User</span>}</Td>
                  <Td className="tnum text-right">{u.listing_count}</Td>
                  <Td className={`tnum text-right ${u.report_count > 0 ? "font-medium text-warning" : ""}`}>
                    {u.report_count}
                  </Td>
                  <Td className="whitespace-nowrap text-text-muted">
                    {new Date(u.created_at).toLocaleDateString("en-IN", { day: "numeric", month: "short", year: "numeric" })}
                  </Td>
                  <Td>
                    {u.is_suspended ? (
                      <Badge variant="warning">Suspended</Badge>
                    ) : (
                      <Badge variant="success">Active</Badge>
                    )}
                  </Td>
                  <Td className="text-right">
                    {u.role !== "ADMIN" && (
                      <Button
                        size="sm"
                        variant="outline"
                        disabled={busyId !== null}
                        onClick={() => toggleSuspend(u)}
                      >
                        {busyId === u.id ? "…" : u.is_suspended ? "Unsuspend" : "Suspend"}
                      </Button>
                    )}
                  </Td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </TableCard>
    </div>
  );
}

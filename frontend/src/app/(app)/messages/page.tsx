import { Suspense } from "react";
import { MessagesScreen } from "./messages-screen";

export const metadata = { title: "Messages" };

export default function MessagesPage() {
  return (
    <Suspense>
      <MessagesScreen />
    </Suspense>
  );
}

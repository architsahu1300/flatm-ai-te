import { Suspense } from "react";
import { FlatmatesScreen } from "./flatmates-screen";

export const metadata = { title: "Find a flatmate" };

export default function FlatmatesPage() {
  return (
    <Suspense>
      <FlatmatesScreen />
    </Suspense>
  );
}

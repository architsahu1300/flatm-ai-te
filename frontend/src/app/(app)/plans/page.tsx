import type { Metadata } from "next";
import { Suspense } from "react";
import { PlansScreen } from "./plans-screen";

export const metadata: Metadata = {
  title: "Plans & boost",
  description: "Upgrade your search or feature your listing.",
};

export default function PlansPage() {
  return (
    <Suspense>
      <PlansScreen />
    </Suspense>
  );
}

import { Suspense } from "react";
import { OnboardingWizard } from "./wizard";

export const metadata = { title: "Set up your profile" };

export default function OnboardingPage() {
  return (
    <Suspense>
      <OnboardingWizard />
    </Suspense>
  );
}

import { Suspense } from "react";
import { AgreementWizard } from "./agreement-wizard";

export const metadata = { title: "New agreement" };

export default function NewAgreementPage() {
  return (
    <Suspense>
      <AgreementWizard />
    </Suspense>
  );
}

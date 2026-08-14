import { Suspense } from "react";
import { ListingWizard } from "../wizard";

export const metadata = { title: "List your place" };

export default function NewListingPage() {
  return (
    <Suspense>
      <ListingWizard />
    </Suspense>
  );
}

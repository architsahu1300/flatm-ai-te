import { Suspense } from "react";
import { ListingWizard } from "../../wizard";

export const metadata = { title: "Edit listing" };

export default async function EditListingPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return (
    <Suspense>
      <ListingWizard listingId={id} />
    </Suspense>
  );
}

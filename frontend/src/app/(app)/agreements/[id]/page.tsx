import { AgreementDetail } from "./agreement-detail";

export const metadata = { title: "Agreement" };

export default async function AgreementPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <AgreementDetail agreementId={id} />;
}

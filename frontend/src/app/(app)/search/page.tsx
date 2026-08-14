import { Suspense } from "react";
import { SearchScreen } from "./search-screen";

export const metadata = { title: "AI Search" };

export default function SearchPage() {
  return (
    <Suspense>
      <SearchScreen />
    </Suspense>
  );
}

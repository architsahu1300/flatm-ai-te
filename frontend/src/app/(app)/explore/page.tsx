import { Suspense } from "react";
import { ExploreScreen } from "./explore-screen";

export const metadata = { title: "Explore homes" };

export default function ExplorePage() {
  return (
    <Suspense>
      <ExploreScreen />
    </Suspense>
  );
}

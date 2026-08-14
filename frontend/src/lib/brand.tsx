/**
 * Single source of truth for the product name. Renaming the app = editing APP_NAME
 * (plus grep for the same string in backend/README if you want full consistency).
 *
 * The Wordmark renders the name as plain styled text — never a baked logo — so the
 * identity survives a rename untouched. If the name contains 'AI' in quotes it gets
 * the brand highlight; any other name renders plain.
 */
export const APP_NAME = "Flatm'AI'te";

export const APP_TAGLINE =
  "Find your next home. Find the right person to share it with.";

export function Wordmark({ className }: { className?: string }) {
  const marker = "'AI'";
  const idx = APP_NAME.indexOf(marker);
  if (idx === -1) {
    return <span className={className}>{APP_NAME}</span>;
  }
  return (
    <span className={className}>
      {APP_NAME.slice(0, idx)}
      <span className="text-brand">&apos;AI&apos;</span>
      {APP_NAME.slice(idx + marker.length)}
    </span>
  );
}

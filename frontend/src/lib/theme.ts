export type ThemeChoice = "light" | "dark" | "system";

export const THEME_STORAGE_KEY = "fm-theme";

/**
 * Runs before first paint (inlined into <head>) so the correct theme is on <html>
 * before React hydrates — otherwise dark users get a white flash on every load.
 * Kept as a string because it must execute synchronously, ahead of the bundle.
 */
export const THEME_INIT_SCRIPT = `
(function(){try{
  var stored = localStorage.getItem('${THEME_STORAGE_KEY}');
  var dark = stored === 'dark' || (stored !== 'light' &&
    window.matchMedia('(prefers-color-scheme: dark)').matches);
  document.documentElement.classList.toggle('dark', dark);
}catch(e){}})();
`.trim();

export function resolveTheme(choice: ThemeChoice): "light" | "dark" {
  if (choice === "system") {
    return window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
  }
  return choice;
}

export function applyTheme(choice: ThemeChoice) {
  const resolved = resolveTheme(choice);
  document.documentElement.classList.toggle("dark", resolved === "dark");
  if (choice === "system") {
    localStorage.removeItem(THEME_STORAGE_KEY);
  } else {
    localStorage.setItem(THEME_STORAGE_KEY, choice);
  }
}

export function readThemeChoice(): ThemeChoice {
  try {
    const stored = localStorage.getItem(THEME_STORAGE_KEY);
    return stored === "dark" || stored === "light" ? stored : "system";
  } catch {
    return "system";
  }
}

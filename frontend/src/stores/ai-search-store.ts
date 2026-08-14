import { create } from "zustand";
import {
  aiApply,
  aiCompare,
  aiRefine,
  aiSearch,
  type AiResult,
  type AiSearchResponse,
  type CompareResponse,
  type Relaxer,
  type SearchIntent,
} from "@/lib/ai-client";
import { ApiError } from "@/lib/api";

type Status = "idle" | "understanding" | "searching" | "done" | "error";

interface Turn {
  role: "user" | "system";
  text: string;
}

interface AiSearchState {
  status: Status;
  sessionId: string | null;
  intent: SearchIntent | null;
  providerMode: "openai" | "mock" | null;
  homes: AiResult[];
  flatmates: AiResult[];
  relaxers: Relaxer[];
  activeTab: "homes" | "flatmates";
  turns: Turn[];
  compareIds: string[];
  comparison: CompareResponse | null;
  compareOpen: boolean;
  error: string | null;

  submit: (query: string) => Promise<void>;
  refine: (query: string) => Promise<void>;
  applyIntent: (intent: SearchIntent, note?: string) => Promise<void>;
  toggleCompare: (id: string) => void;
  runComparison: () => Promise<void>;
  closeComparison: () => void;
  setActiveTab: (tab: "homes" | "flatmates") => void;
  reset: () => void;
}

const MIN_UNDERSTANDING_MS = 700;

function resultId(r: AiResult): string {
  return r.home?.id ?? r.flatmate?.id ?? "";
}

export const useAiSearchStore = create<AiSearchState>()((set, get) => {
  async function run(action: () => Promise<AiSearchResponse>, userText: string) {
    const started = Date.now();
    set({
      status: "understanding",
      error: null,
      comparison: null,
      compareOpen: false,
      turns: [...get().turns, { role: "user", text: userText }],
    });
    try {
      const response = await action();
      const elapsed = Date.now() - started;
      if (elapsed < MIN_UNDERSTANDING_MS) {
        await new Promise((resolve) => setTimeout(resolve, MIN_UNDERSTANDING_MS - elapsed));
      }
      const activeTab =
        response.homes.length === 0 && response.flatmates.length > 0 ? "flatmates" : "homes";
      set({
        status: "done",
        sessionId: response.sessionId,
        intent: response.intent,
        providerMode: response.providerMode,
        homes: response.homes,
        flatmates: response.flatmates,
        relaxers: response.relaxers,
        activeTab,
        compareIds: [],
      });
    } catch (e) {
      set({
        status: "error",
        error: e instanceof ApiError ? e.message : "Something went wrong — try again",
      });
    }
  }

  return {
    status: "idle",
    sessionId: null,
    intent: null,
    providerMode: null,
    homes: [],
    flatmates: [],
    relaxers: [],
    activeTab: "homes",
    turns: [],
    compareIds: [],
    comparison: null,
    compareOpen: false,
    error: null,

    submit: (query) => run(() => aiSearch(query, get().sessionId), query),

    refine: (query) => {
      const sessionId = get().sessionId;
      if (!sessionId) {
        return get().submit(query);
      }
      return run(() => aiRefine(query, sessionId), query);
    },

    applyIntent: (intent, note) => {
      const sessionId = get().sessionId;
      if (!sessionId) {
        return Promise.resolve();
      }
      return run(() => aiApply(intent, sessionId), note ?? "(edited requirements)");
    },

    toggleCompare: (id) => {
      const current = get().compareIds;
      if (current.includes(id)) {
        set({ compareIds: current.filter((x) => x !== id) });
      } else if (current.length < 3) {
        set({ compareIds: [...current, id] });
      }
    },

    runComparison: async () => {
      const { sessionId, compareIds } = get();
      if (!sessionId || compareIds.length < 2) {
        return;
      }
      try {
        const comparison = await aiCompare(compareIds, sessionId);
        set({ comparison, compareOpen: true });
      } catch (e) {
        set({ error: e instanceof ApiError ? e.message : "Comparison failed" });
      }
    },

    closeComparison: () => set({ compareOpen: false }),

    setActiveTab: (tab) => set({ activeTab: tab }),

    reset: () =>
      set({
        status: "idle",
        sessionId: null,
        intent: null,
        homes: [],
        flatmates: [],
        relaxers: [],
        turns: [],
        compareIds: [],
        comparison: null,
        compareOpen: false,
        error: null,
      }),
  };
});

export { resultId };

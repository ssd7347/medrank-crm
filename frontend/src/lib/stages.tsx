"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";

import { api } from "./api";
import type { Stage } from "./types";

// The stages each customer names for themselves ("New → Visited → Booked"), loaded once for the signed-in
// screens. Contacts store a stage key; everything shown to people goes through label()/color().

type StagesState = {
  stages: Stage[];
  label: (key: string) => string;
  color: (key: string) => string;
  reload: () => void;
};

const fallbackLabel = (key: string) => {
  const t = key.toLowerCase().replace(/_/g, " ");
  return t.charAt(0).toUpperCase() + t.slice(1);
};

const StagesContext = createContext<StagesState>({ stages: [], label: fallbackLabel, color: () => "#64748b", reload: () => undefined });

export function StagesProvider({ children }: { children: React.ReactNode }) {
  const [stages, setStages] = useState<Stage[]>([]);
  const [version, setVersion] = useState(0);

  useEffect(() => {
    api<Stage[]>("/api/stages")
      .then(setStages)
      .catch(() => undefined);
  }, [version]);

  const reload = useCallback(() => setVersion((v) => v + 1), []);
  const value = useMemo<StagesState>(() => {
    const byKey = new Map(stages.map((s) => [s.key, s]));
    return {
      stages,
      label: (key) => byKey.get(key)?.label ?? fallbackLabel(key),
      color: (key) => byKey.get(key)?.color ?? "#64748b",
      reload,
    };
  }, [stages, reload]);

  return <StagesContext.Provider value={value}>{children}</StagesContext.Provider>;
}

export function useStages() {
  return useContext(StagesContext);
}

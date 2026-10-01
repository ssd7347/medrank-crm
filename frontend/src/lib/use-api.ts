"use client";

import { useCallback, useEffect, useRef, useState } from "react";

import { api, errorMessage } from "./api";

type Query = Record<string, string | number | boolean | null | undefined>;

/**
 * Loads a GET endpoint and reloads whenever the path or query changes. Stale responses from earlier
 * requests are ignored so fast filter changes never show out-of-date results.
 */
export function useApi<T>(path: string | null, query?: Query) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [version, setVersion] = useState(0);
  // The request key whose response is currently shown; "loading" is derived from it.
  const [settledKey, setSettledKey] = useState<string | null>(null);
  const latestKey = useRef<string | null>(null);
  const queryKey = JSON.stringify(query ?? {});
  const key = path === null ? null : `${path}|${queryKey}|${version}`;

  useEffect(() => {
    if (path === null || key === null) return;
    latestKey.current = key;
    api<T>(path, { query: JSON.parse(queryKey) as Query })
      .then((d) => {
        if (latestKey.current !== key) return;
        setData(d);
        setError(null);
        setSettledKey(key);
      })
      .catch((e) => {
        if (latestKey.current !== key) return;
        setError(errorMessage(e));
        setSettledKey(key);
      });
  }, [path, queryKey, key]);

  const reload = useCallback(() => setVersion((v) => v + 1), []);
  const loading = key !== null && settledKey !== key;
  return { data, error, loading, reload, setData };
}

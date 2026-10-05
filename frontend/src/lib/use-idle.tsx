"use client";

import { useEffect, useRef, useState } from "react";

/** Must match app.jwt.refresh-ttl on the backend, which enforces the same limit for closed or sleeping tabs. */
export const IDLE_TIMEOUT_MS = 3 * 60 * 1000;
const WARN_BEFORE_MS = 30 * 1000;
const KEEP_ALIVE_EVERY_MS = 60 * 1000;
const EVENTS = ["pointerdown", "pointermove", "keydown", "wheel", "scroll", "touchstart"] as const;

function read(key: string): number {
  try {
    return Number(window.localStorage.getItem(key)) || 0;
  } catch {
    return 0;
  }
}

function write(key: string, value: number) {
  try {
    window.localStorage.setItem(key, String(value));
  } catch {
    // Private mode or blocked storage: this tab still tracks its own activity in memory.
  }
}

/**
 * Signs the user out after {@link IDLE_TIMEOUT_MS} without mouse, keyboard or touch activity.
 *
 * Activity is shared between tabs through localStorage, so working in one tab keeps the others signed in.
 * While the user is active the session is renewed about once a minute (`keepAlive`), because the server
 * expires it on the same clock. Returns the seconds left once the warning period starts, else null.
 */
export function useIdleLogout({ enabled, storageKey, keepAlive, onIdle }: { enabled: boolean; storageKey: string; keepAlive: () => unknown; onIdle: () => void }) {
  const [secondsLeft, setSecondsLeft] = useState<number | null>(null);
  const handlers = useRef({ keepAlive, onIdle });
  useEffect(() => {
    handlers.current = { keepAlive, onIdle };
  });

  useEffect(() => {
    if (!enabled) return;
    const activityKey = `${storageKey}:activity`;
    const renewedKey = `${storageKey}:renewed`;
    let lastActivity = Date.now();
    let lastWrite = 0;
    let fired = false;
    write(activityKey, lastActivity);
    write(renewedKey, lastActivity);

    const onActivity = () => {
      const now = Date.now();
      lastActivity = now;
      // Throttle storage writes and renewals; pointer moves fire many times a second.
      if (now - lastWrite < 2000) return;
      lastWrite = now;
      write(activityKey, now);
      if (now - read(renewedKey) >= KEEP_ALIVE_EVERY_MS) {
        write(renewedKey, now);
        handlers.current.keepAlive();
      }
    };

    const tick = () => {
      if (fired) return;
      const idleFor = Date.now() - Math.max(lastActivity, read(activityKey));
      if (idleFor >= IDLE_TIMEOUT_MS) {
        fired = true;
        setSecondsLeft(null);
        handlers.current.onIdle();
      } else if (idleFor >= IDLE_TIMEOUT_MS - WARN_BEFORE_MS) {
        setSecondsLeft(Math.ceil((IDLE_TIMEOUT_MS - idleFor) / 1000));
      } else {
        setSecondsLeft(null);
      }
    };

    EVENTS.forEach((e) => window.addEventListener(e, onActivity, { passive: true }));
    // A laptop waking from sleep may not have run the timer; check as soon as the tab is looked at again.
    document.addEventListener("visibilitychange", tick);
    const timer = window.setInterval(tick, 1000);
    return () => {
      EVENTS.forEach((e) => window.removeEventListener(e, onActivity));
      document.removeEventListener("visibilitychange", tick);
      window.clearInterval(timer);
      setSecondsLeft(null);
    };
  }, [enabled, storageKey]);

  return secondsLeft;
}

/** Shown for the last 30 seconds. Any mouse movement or key press dismisses it by itself. */
export function IdleWarning({ secondsLeft }: { secondsLeft: number | null }) {
  if (secondsLeft === null) return null;
  return (
    <div role="alert" className="fixed inset-x-4 bottom-4 z-50 mx-auto max-w-sm rounded-lg border border-amber-300 bg-amber-50 px-4 py-3 text-sm text-amber-900 shadow-lg print:hidden">
      <p className="font-medium">Still there?</p>
      <p>
        You will be signed out in <span className="tabular-nums">{secondsLeft}</span> seconds. Move the mouse or press a key to stay signed in.
      </p>
    </div>
  );
}

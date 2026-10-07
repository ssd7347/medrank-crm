"use client";

import { useCallback, useEffect, useRef, useState } from "react";

import { postForBlob } from "./api";

// Talking to the AI agent through the laptop microphone. The browser listens, notices when the person starts
// and stops speaking (by loudness), and hands each finished sentence over as an audio clip; the agent's replies
// are synthesized by our speech engine and played back. While the agent speaks, listening pauses, so it never
// hears itself.

export type VoiceState = "off" | "listening" | "hearing" | "thinking" | "speaking";

/** Silence that ends a sentence, and the longest single sentence we record. */
const END_SILENCE_MS = 900;
const MAX_UTTERANCE_MS = 20_000;

export function useVoice() {
  const [state, setState] = useState<VoiceState>("off");
  const [level, setLevel] = useState(0);
  const stream = useRef<MediaStream | null>(null);
  const ctx = useRef<AudioContext | null>(null);
  const analyser = useRef<AnalyserNode | null>(null);
  const recorder = useRef<MediaRecorder | null>(null);
  const timer = useRef<number | null>(null);
  const audio = useRef<HTMLAudioElement | null>(null);
  const handler = useRef<(audio: Blob) => void>(() => undefined);
  const stateRef = useRef<VoiceState>("off");

  /** What to do with each recorded sentence. */
  const onUtterance = useCallback((fn: (audio: Blob) => void) => {
    handler.current = fn;
  }, []);

  const set = (s: VoiceState) => {
    stateRef.current = s;
    setState(s);
  };

  const stopLoop = () => {
    if (timer.current !== null) window.clearInterval(timer.current);
    timer.current = null;
  };

  const stop = useCallback(() => {
    stopLoop();
    if (recorder.current && recorder.current.state !== "inactive") {
      recorder.current.onstop = null;
      recorder.current.stop();
    }
    recorder.current = null;
    audio.current?.pause();
    stream.current?.getTracks().forEach((t) => t.stop());
    stream.current = null;
    void ctx.current?.close();
    ctx.current = null;
    analyser.current = null;
    set("off");
    setLevel(0);
  }, []);

  useEffect(() => stop, [stop]);

  /** Opens the microphone (the browser asks the person the first time). */
  const open = useCallback(async () => {
    if (stream.current) return;
    const s = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true } });
    stream.current = s;
    const c = new AudioContext();
    const a = c.createAnalyser();
    a.fftSize = 1024;
    c.createMediaStreamSource(s).connect(a);
    ctx.current = c;
    analyser.current = a;
  }, []);

  /** Waits for one spoken sentence, then hands the recording to {@code onUtterance}. */
  const listen = useCallback(() => {
    const s = stream.current;
    const a = analyser.current;
    if (!s || !a) return;
    stopLoop();
    const chunks: Blob[] = [];
    const mime = MediaRecorder.isTypeSupported("audio/webm;codecs=opus") ? "audio/webm;codecs=opus" : "";
    const rec = new MediaRecorder(s, mime ? { mimeType: mime } : undefined);
    recorder.current = rec;
    rec.ondataavailable = (e) => e.data.size && chunks.push(e.data);
    let spoke = false;
    rec.onstop = () => {
      if (spoke) handler.current(new Blob(chunks, { type: rec.mimeType || "audio/webm" }));
    };
    rec.start(250);
    set("listening");

    const buf = new Float32Array(a.fftSize);
    const started = performance.now();
    let floor = 0.01;
    let loudSince = 0;
    let quietSince = 0;
    let speechStart = 0;
    timer.current = window.setInterval(() => {
      a.getFloatTimeDomainData(buf);
      let sum = 0;
      for (const v of buf) sum += v * v;
      const rms = Math.sqrt(sum / buf.length);
      setLevel(Math.min(1, rms * 12));
      const now = performance.now();
      // Learn the room's background noise in the first half second; speech is clearly louder than it.
      if (now - started < 500) {
        floor = Math.max(floor, rms);
        return;
      }
      const threshold = Math.max(0.018, floor * 2.5);
      if (rms > threshold) {
        quietSince = 0;
        if (!loudSince) loudSince = now;
        if (!spoke && now - loudSince > 120) {
          spoke = true;
          speechStart = now;
          set("hearing");
        }
      } else {
        loudSince = 0;
        if (spoke && !quietSince) quietSince = now;
      }
      const done = spoke && ((quietSince && now - quietSince > END_SILENCE_MS) || now - speechStart > MAX_UTTERANCE_MS);
      if (done) {
        stopLoop();
        setLevel(0);
        set("thinking");
        rec.stop();
      }
    }, 50);
  }, []);

  /** Speaks each line with our speech engine's voice, one after the other. Listening is paused meanwhile. */
  const speak = useCallback(async (lines: string[]) => {
    if (!lines.length) return;
    set("speaking");
    for (const line of lines) {
      if (stateRef.current === "off") return;
      const blob = await postForBlob("/api/voice/speech/tts", { text: line });
      const url = URL.createObjectURL(blob);
      const el = new Audio(url);
      audio.current = el;
      await new Promise<void>((resolve) => {
        el.onended = () => resolve();
        el.onerror = () => resolve();
        el.play().catch(() => resolve());
      });
      URL.revokeObjectURL(url);
    }
  }, []);

  const setThinking = useCallback(() => set("thinking"), []);
  return { state, level, open, listen, speak, stop, setThinking, onUtterance };
}

"use client";

import { useEffect, useRef, useState } from "react";

import { Logo } from "@/components/logo";
import { Alert, Button, Field, Input, Select, cx } from "@/components/ui";
import { INDIAN_STATES } from "@/lib/format";
import { CATEGORIES, LANGUAGES, type Language } from "@/lib/types";

// Public website assistant: no login. It talks only to /api/public/assistant.

type Faq = { id: number; question: string; answer: string };
type Message = { from: "bot" | "user"; text: string; offerCallback?: boolean };

const T: Record<Language, Record<string, string>> = {
  ENGLISH: {
    name: "English",
    title: "Admission counselling assistant",
    hello: "Hello! Ask me about MBBS/BDS counselling, or pick a question below. For advice about your own rank, a counsellor will call you.",
    placeholder: "Type your question",
    send: "Send",
    common: "Common questions",
    noAnswer: "I don't have a ready answer for that. A counsellor can call you and explain.",
    callback: "Ask a counsellor to call me",
    formTitle: "We will call you back",
    formName: "Your name",
    formPhone: "Mobile number",
    formScore: "NEET score (if you know it)",
    formCategory: "Category",
    formState: "Home state",
    optional: "Not sure",
    submit: "Request a call",
    cancel: "Cancel",
    thanks: "Thank you. A counsellor will call you, usually within one working day.",
    error: "Something went wrong. Please try again in a little while.",
    consent: "By sending this you agree to be contacted by phone or WhatsApp about admission counselling.",
    disclaimer: "General information only. Seats are allotted by the counselling authorities; nobody can guarantee one.",
    related: "You may also want to know",
  },
  TAMIL: {
    name: "தமிழ்",
    title: "சேர்க்கை ஆலோசனை உதவியாளர்",
    hello: "வணக்கம்! MBBS/BDS கலந்தாய்வு பற்றி கேளுங்கள், அல்லது கீழே உள்ள கேள்வியைத் தேர்ந்தெடுங்கள். உங்கள் தரவரிசைக்கான ஆலோசனைக்கு, ஆலோசகர் உங்களை அழைப்பார்.",
    placeholder: "உங்கள் கேள்வியை எழுதுங்கள்",
    send: "அனுப்பு",
    common: "பொதுவான கேள்விகள்",
    noAnswer: "இதற்கு என்னிடம் தயாரான பதில் இல்லை. ஆலோசகர் உங்களை அழைத்து விளக்குவார்.",
    callback: "ஆலோசகர் என்னை அழைக்கட்டும்",
    formTitle: "நாங்கள் உங்களை அழைக்கிறோம்",
    formName: "உங்கள் பெயர்",
    formPhone: "கைபேசி எண்",
    formScore: "NEET மதிப்பெண் (தெரிந்தால்)",
    formCategory: "பிரிவு",
    formState: "சொந்த மாநிலம்",
    optional: "தெரியவில்லை",
    submit: "அழைப்பைக் கோருங்கள்",
    cancel: "ரத்து",
    thanks: "நன்றி. ஆலோசகர் பொதுவாக ஒரு வேலை நாளுக்குள் உங்களை அழைப்பார்.",
    error: "ஏதோ தவறு நடந்தது. சிறிது நேரம் கழித்து மீண்டும் முயற்சிக்கவும்.",
    consent: "இதை அனுப்புவதன் மூலம், சேர்க்கை ஆலோசனை குறித்து தொலைபேசி அல்லது WhatsApp மூலம் தொடர்பு கொள்ள ஒப்புக்கொள்கிறீர்கள்.",
    disclaimer: "பொதுத் தகவல் மட்டுமே. இடங்களை கலந்தாய்வு அதிகாரிகளே ஒதுக்குகிறார்கள்; யாராலும் உத்தரவாதம் தர முடியாது.",
    related: "இவற்றையும் தெரிந்து கொள்ளலாம்",
  },
  HINDI: {
    name: "हिन्दी",
    title: "प्रवेश काउंसलिंग सहायक",
    hello: "नमस्ते! MBBS/BDS काउंसलिंग के बारे में पूछें, या नीचे से कोई प्रश्न चुनें। आपकी रैंक के अनुसार सलाह के लिए काउंसलर आपको कॉल करेगा।",
    placeholder: "अपना प्रश्न लिखें",
    send: "भेजें",
    common: "सामान्य प्रश्न",
    noAnswer: "इसका तैयार उत्तर मेरे पास नहीं है। काउंसलर आपको कॉल करके समझा सकता है।",
    callback: "काउंसलर मुझे कॉल करे",
    formTitle: "हम आपको कॉल करेंगे",
    formName: "आपका नाम",
    formPhone: "मोबाइल नंबर",
    formScore: "NEET स्कोर (यदि पता हो)",
    formCategory: "श्रेणी",
    formState: "गृह राज्य",
    optional: "पता नहीं",
    submit: "कॉल का अनुरोध करें",
    cancel: "रद्द करें",
    thanks: "धन्यवाद। काउंसलर आम तौर पर एक कार्य दिवस के भीतर आपको कॉल करेगा।",
    error: "कुछ गड़बड़ हुई। कृपया थोड़ी देर बाद फिर प्रयास करें।",
    consent: "इसे भेजकर आप प्रवेश काउंसलिंग के बारे में फ़ोन या WhatsApp पर संपर्क किए जाने के लिए सहमत हैं।",
    disclaimer: "केवल सामान्य जानकारी। सीटें काउंसलिंग प्राधिकरण आवंटित करते हैं; कोई भी गारंटी नहीं दे सकता।",
    related: "आप यह भी जानना चाहेंगे",
  },
};

async function call<R>(path: string, body?: unknown): Promise<R> {
  const res = await fetch(path, body === undefined ? undefined : { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
  if (!res.ok) throw new Error(String(res.status));
  const text = await res.text();
  return (text ? JSON.parse(text) : null) as R;
}

export default function AssistantPage() {
  const [language, setLanguage] = useState<Language>("ENGLISH");
  const t = T[language];
  const [faqs, setFaqs] = useState<Faq[]>([]);
  const [messages, setMessages] = useState<Message[]>([]);
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const [formOpen, setFormOpen] = useState(false);
  const [done, setDone] = useState(false);
  const [error, setError] = useState(false);
  const end = useRef<HTMLDivElement>(null);

  useEffect(() => {
    call<Faq[]>(`/api/public/assistant/faq?language=${language}`)
      .then(setFaqs)
      .catch(() => setFaqs([]));
  }, [language]);

  useEffect(() => {
    end.current?.scrollIntoView({ block: "end" });
  }, [messages, formOpen, done]);

  async function ask(question: string) {
    const q = question.trim();
    if (!q || busy) return;
    setInput("");
    setError(false);
    setMessages((m) => [...m, { from: "user", text: q }]);
    setBusy(true);
    try {
      const r = await call<{ answer: Faq | null; related: Faq[] }>("/api/public/assistant/ask", { language, question: q });
      setMessages((m) => [...m, r.answer ? { from: "bot", text: r.answer.answer, offerCallback: true } : { from: "bot", text: t.noAnswer, offerCallback: true }]);
    } catch {
      setError(true);
    } finally {
      setBusy(false);
    }
  }

  function showFaq(f: Faq) {
    setMessages((m) => [...m, { from: "user", text: f.question }, { from: "bot", text: f.answer, offerCallback: true }]);
  }

  return (
    <div className="flex min-h-screen flex-col bg-muted">
      <header className="sticky top-0 z-10 border-b border-line bg-surface">
        <div className="mx-auto flex max-w-2xl items-center justify-between gap-3 px-4 py-3">
          <Logo />
          <div className="flex gap-1" role="group" aria-label="Language">
            {LANGUAGES.map((l) => (
              <button
                key={l}
                onClick={() => setLanguage(l)}
                aria-pressed={language === l}
                className={cx("rounded-full border px-2.5 py-1 text-xs", language === l ? "border-brand-600 bg-brand-600 text-white" : "border-line bg-surface")}
              >
                {T[l].name}
              </button>
            ))}
          </div>
        </div>
      </header>

      <main className="mx-auto flex w-full max-w-2xl flex-1 flex-col gap-3 px-4 py-5">
        <h1 className="text-lg font-semibold tracking-tight">{t.title}</h1>
        <Bubble from="bot">{t.hello}</Bubble>

        {messages.map((m, i) => (
          <Bubble key={i} from={m.from}>
            {m.text}
            {m.offerCallback && i === messages.length - 1 && !done && !formOpen && (
              <button onClick={() => setFormOpen(true)} className="mt-2 block rounded-lg bg-brand-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-brand-700">
                {t.callback}
              </button>
            )}
          </Bubble>
        ))}
        {error && <Alert>{t.error}</Alert>}

        {formOpen && !done && (
          <CallbackForm
            t={t}
            language={language}
            questions={messages.filter((m) => m.from === "user").map((m) => m.text).slice(-10)}
            onCancel={() => setFormOpen(false)}
            onDone={() => {
              setDone(true);
              setFormOpen(false);
            }}
          />
        )}
        {done && <Alert tone="green">{t.thanks}</Alert>}

        {!formOpen && faqs.length > 0 && (
          <section className="mt-2">
            <h2 className="mb-1.5 text-xs font-medium text-ink-soft">{messages.length ? t.related : t.common}</h2>
            <div className="flex flex-wrap gap-1.5">
              {faqs
                .filter((f) => !messages.some((m) => m.text === f.question))
                .slice(0, 5)
                .map((f) => (
                  <button key={f.id} onClick={() => showFaq(f)} className="rounded-full border border-line bg-surface px-3 py-1.5 text-left text-sm hover:bg-brand-50">
                    {f.question}
                  </button>
                ))}
              {!done && !messages.length && (
                <button onClick={() => setFormOpen(true)} className="rounded-full border border-brand-600 px-3 py-1.5 text-sm font-medium text-brand-800 hover:bg-brand-50">
                  {t.callback}
                </button>
              )}
            </div>
          </section>
        )}
        <div ref={end} />
      </main>

      <footer className="sticky bottom-0 border-t border-line bg-surface">
        <form
          onSubmit={(e) => {
            e.preventDefault();
            ask(input);
          }}
          className="mx-auto flex max-w-2xl gap-2 px-4 py-3"
        >
          <Input value={input} onChange={(e) => setInput(e.target.value)} placeholder={t.placeholder} aria-label={t.placeholder} maxLength={300} />
          <Button type="submit" loading={busy} disabled={!input.trim()}>
            {t.send}
          </Button>
        </form>
        <p className="mx-auto max-w-2xl px-4 pb-3 text-[11px] text-ink-faint">{t.disclaimer}</p>
      </footer>
    </div>
  );
}

function Bubble({ from, children }: { from: "bot" | "user"; children: React.ReactNode }) {
  return (
    <div className={cx("max-w-[85%] rounded-2xl px-3.5 py-2.5 text-sm whitespace-pre-wrap", from === "bot" ? "self-start border border-line bg-surface" : "self-end bg-brand-600 text-white")}>
      {children}
    </div>
  );
}

function CallbackForm({
  t,
  language,
  questions,
  onCancel,
  onDone,
}: {
  t: Record<string, string>;
  language: Language;
  questions: string[];
  onCancel: () => void;
  onDone: () => void;
}) {
  const [v, setV] = useState({ fullName: "", phone: "", neetScore: "", category: "", homeState: "", website: "" });
  const [saving, setSaving] = useState(false);
  const [failed, setFailed] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setSaving(true);
    setFailed(false);
    try {
      await call("/api/public/assistant/callback", {
        fullName: v.fullName,
        phone: v.phone,
        neetScore: v.neetScore === "" ? null : Number(v.neetScore),
        category: v.category || null,
        homeState: v.homeState || null,
        language,
        questions,
        website: v.website,
      });
      onDone();
    } catch {
      setFailed(true);
    } finally {
      setSaving(false);
    }
  }

  return (
    <form onSubmit={submit} className="space-y-3 rounded-2xl border border-line bg-surface p-4">
      <h2 className="text-sm font-semibold">{t.formTitle}</h2>
      {failed && <Alert>{t.error}</Alert>}
      <Field label={t.formName} required>
        {(id) => <Input id={id} required maxLength={120} autoComplete="name" value={v.fullName} onChange={(e) => setV({ ...v, fullName: e.target.value })} />}
      </Field>
      <Field label={t.formPhone} required>
        {(id) => <Input id={id} required type="tel" inputMode="numeric" pattern="[0-9+ -]{10,20}" autoComplete="tel" value={v.phone} onChange={(e) => setV({ ...v, phone: e.target.value })} />}
      </Field>
      <div className="grid gap-3 sm:grid-cols-3">
        <Field label={t.formScore}>
          {(id) => <Input id={id} type="number" inputMode="numeric" min={0} max={720} value={v.neetScore} onChange={(e) => setV({ ...v, neetScore: e.target.value })} />}
        </Field>
        <Field label={t.formCategory}>{(id) => <Select id={id} value={v.category} onChange={(e) => setV({ ...v, category: e.target.value })} options={CATEGORIES} placeholder={t.optional} />}</Field>
        <Field label={t.formState}>{(id) => <Select id={id} value={v.homeState} onChange={(e) => setV({ ...v, homeState: e.target.value })} options={INDIAN_STATES} placeholder={t.optional} />}</Field>
      </div>
      {/* Hidden from people; bots that fill every field give themselves away. */}
      <div className="hidden" aria-hidden>
        <label>
          Website
          <input tabIndex={-1} autoComplete="off" value={v.website} onChange={(e) => setV({ ...v, website: e.target.value })} />
        </label>
      </div>
      <p className="text-xs text-ink-faint">{t.consent}</p>
      <div className="flex gap-2">
        <Button type="submit" loading={saving}>
          {t.submit}
        </Button>
        <Button type="button" variant="ghost" onClick={onCancel}>
          {t.cancel}
        </Button>
      </div>
    </form>
  );
}

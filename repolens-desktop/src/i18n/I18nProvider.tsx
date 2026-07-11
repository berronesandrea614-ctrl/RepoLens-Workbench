import {
  createContext,
  useCallback,
  useContext,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import { zh, type MessageKey } from "./zh";
import { en } from "./en";

/**
 * 轻量 i18n —— React Context + t() + 两个字典（zh/en），不引任何第三方库。
 *
 * - 默认 locale = zh；从 localStorage['repolens.locale'] 读取（值 'zh' | 'en'）。
 * - setLocale 写回 localStorage 并驱动 Context 重渲染（全局界面即时切换）。
 * - t(key, fallback?)：en 缺失该 key 时回退到 zh 原文；zh 也缺失时回退到显式
 *   fallback（调用点都应带中文兜底），再没有才回退 key 名——确保永不显示成裸 key。
 */

export type Locale = "zh" | "en";

const LOCALE_KEY = "repolens.locale";

export function readLocale(): Locale {
  try {
    return localStorage.getItem(LOCALE_KEY) === "en" ? "en" : "zh";
  } catch {
    return "zh";
  }
}

export type TFn = (key: MessageKey, fallback?: string) => string;

interface I18nContextValue {
  locale: Locale;
  setLocale: (next: Locale) => void;
  t: TFn;
}

const I18nContext = createContext<I18nContextValue | null>(null);

export function I18nProvider({ children }: { children: ReactNode }) {
  const [locale, setLocaleState] = useState<Locale>(readLocale);

  const setLocale = useCallback((next: Locale) => {
    setLocaleState(next);
    try {
      localStorage.setItem(LOCALE_KEY, next);
    } catch {
      /* ignore storage failures — in-memory state still switches the UI */
    }
  }, []);

  const t = useCallback<TFn>(
    (key, fallback) => {
      if (locale === "en") {
        const hit = en[key];
        if (hit != null) return hit;
      }
      // zh 是真源；缺失才用显式兜底，再没有才用 key 名。
      return zh[key] ?? fallback ?? key;
    },
    [locale],
  );

  const value = useMemo<I18nContextValue>(
    () => ({ locale, setLocale, t }),
    [locale, setLocale, t],
  );

  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

export function useI18n(): I18nContextValue {
  const ctx = useContext(I18nContext);
  if (!ctx) {
    // 容错：未套 Provider 时退化为默认中文，避免崩溃（不应在生产触发）。
    return {
      locale: "zh",
      setLocale: () => {},
      t: (key, fallback) => zh[key] ?? fallback ?? key,
    };
  }
  return ctx;
}

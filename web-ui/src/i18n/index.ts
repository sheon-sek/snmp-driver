import { createInstance } from "i18next";
import { initReactI18next } from "react-i18next";

import enCommon from "./locales/en-US/common.json";
import zhCNCommon from "./locales/zh-CN/common.json";
import zhTWCommon from "./locales/zh-TW/common.json";
import koCommon from "./locales/ko-KR/common.json";
import deCommon from "./locales/de-DE/common.json";
import esCommon from "./locales/es-ES/common.json";

export const supportedLanguages = [
  {
    code: "en-US",
    label: "English",
    labelKey: "general.controls.language.english",
    resource: { common: enCommon },
  },
  {
    code: "zh-CN",
    label: "简体中文",
    labelKey: "general.controls.language.simplifiedChinese",
    resource: { common: zhCNCommon },
  },
  {
    code: "zh-TW",
    label: "繁體中文",
    labelKey: "general.controls.language.traditionalChinese",
    resource: { common: zhTWCommon },
  },
  {
    code: "ko-KR",
    label: "한국어",
    labelKey: "general.controls.language.korean",
    resource: { common: koCommon },
  },
  {
    code: "de-DE",
    label: "Deutsch",
    labelKey: "general.controls.language.german",
    resource: { common: deCommon },
  },
  {
    code: "es-ES",
    label: "Español",
    labelKey: "general.controls.language.spanish",
    resource: { common: esCommon },
  },
] as const;

export type AppLanguage = (typeof supportedLanguages)[number]["code"];

export const defaultLanguage: AppLanguage = "en-US";
export const supportedLanguageCodes = supportedLanguages.map(
  (language) => language.code,
) as AppLanguage[];

export function createAppI18n() {
  const instance = createInstance();
  void instance.use(initReactI18next).init({
    resources: Object.fromEntries(
      supportedLanguages.map((language) => [language.code, language.resource]),
    ),
    lng: defaultLanguage,
    fallbackLng: defaultLanguage,
    ns: ["common"],
    defaultNS: "common",
    interpolation: {
      escapeValue: false,
    },
  });
  return instance;
}

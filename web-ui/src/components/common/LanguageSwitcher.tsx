import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Button } from "../ui/button";
import { Languages } from "lucide-react";
import { useAppDispatch, useAppSelector } from "@/store";
import { setLanguage } from "@/store/uiPreferencesSlice";
import { supportedLanguages, type AppLanguage } from "@/i18n";
import { useTranslation } from "react-i18next";

export function LanguageSwitcher() {
  const dispatch = useAppDispatch();
  const language = useAppSelector((state) => state.uiPreferences.language);
  const { t } = useTranslation();

  const handleLanguageChange = (lang: AppLanguage) => {
    dispatch(setLanguage(lang));
  };

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button
          variant="outline"
          size="icon"
          aria-label={t("general.controls.language.trigger")}
        >
          <Languages className="h-5 w-5" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        {supportedLanguages.map((lang) => (
          <DropdownMenuItem
            key={lang.code}
            className={language === lang.code ? "font-bold" : "opacity-70"}
            onClick={() => handleLanguageChange(lang.code)}
          >
            {t(lang.labelKey, lang.label)}
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

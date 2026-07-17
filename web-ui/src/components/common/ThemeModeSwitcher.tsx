import { Sun, Moon } from "lucide-react"
import { Button } from "@/components/ui/button"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import {
  useUiPreferencesDispatch,
  useUiPreferencesSelector,
} from "@/store/uiPreferencesHooks";
import { setThemeMode, type AppThemeMode } from "@/store/uiPreferencesSlice";
import { useTranslation } from "react-i18next";

export function ThemeModeSwitcher() {
    const dispatch = useUiPreferencesDispatch();
    const themeMode = useUiPreferencesSelector((state) => state.uiPreferences.themeMode)
    const { t } = useTranslation();

    function getResolvedTheme(theme:AppThemeMode) {
        return themeMode === "system"
            ? window.matchMedia("(prefers-color-scheme: dark)").matches
                ? "dark"
                : "light"
            : themeMode
    }

    return (
        <DropdownMenu>
            <DropdownMenuTrigger asChild>
                <Button variant="outline" size="icon" aria-label={t("general.controls.theme.trigger")}>
                    {
                        getResolvedTheme(themeMode) === 'dark' ?
                        <Moon className="h-5 w-5 rotate-90 scale-0 transition-all dark:rotate-0 dark:scale-100"/>
                        : <Sun className="h-5 w-5 rotate-0 scale-100 transition-all dark:-rotate-90 dark:scale-0"/> 
                    }
                </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end">
                <DropdownMenuItem className={themeMode === 'dark' ? 'font-bold' : 'opacity-70'} onClick={() => dispatch(setThemeMode("dark"))}>
                    {t("general.controls.theme.dark")}
                </DropdownMenuItem>
                <DropdownMenuItem className={themeMode === 'light' ? 'font-bold' : 'opacity-70'} onClick={() => dispatch(setThemeMode("light"))}>
                    {t("general.controls.theme.light")}
                </DropdownMenuItem>
                <DropdownMenuItem className={themeMode === 'system' ? 'font-bold' : 'opacity-70'} onClick={() => dispatch(setThemeMode("system"))}>
                    {t("general.controls.theme.system")}
                </DropdownMenuItem>
            </DropdownMenuContent>
        </DropdownMenu>
    )
}

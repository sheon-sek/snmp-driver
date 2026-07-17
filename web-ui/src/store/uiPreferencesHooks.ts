import { useDispatch, useSelector } from "react-redux";

import type { UiPreferencesState } from "@/store/uiPreferencesSlice";

export interface UiPreferencesRootState {
  uiPreferences: UiPreferencesState;
}

export function useUiPreferencesDispatch() {
  return useDispatch();
}

export function useUiPreferencesSelector<TSelected>(
  selector: (state: UiPreferencesRootState) => TSelected,
) {
  return useSelector(selector);
}

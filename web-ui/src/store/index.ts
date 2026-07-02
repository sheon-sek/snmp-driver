import { configureStore } from "@reduxjs/toolkit";
import {
  useDispatch,
  useSelector,
  type TypedUseSelectorHook,
} from "react-redux";
import { baseApi } from "@/lib/api";
import {
  loadPersistedUiPreferences,
  persistUiPreferences,
  uiPreferencesReducer,
} from "@/store/uiPreferencesSlice";

export const store = configureStore({
  reducer: {
    uiPreferences: uiPreferencesReducer,
    [baseApi.reducerPath]: baseApi.reducer,
  },
  preloadedState: {
    uiPreferences: loadPersistedUiPreferences(),
  },
  middleware: (getDefaultMiddleware) =>
    getDefaultMiddleware().concat(baseApi.middleware),
});

// Persist uiPreferences state to localStorage whenever it changes
let lastUiPreferencesState = store.getState().uiPreferences;
store.subscribe(() => {
  const nextUiPreferencesState = store.getState().uiPreferences;

  if (nextUiPreferencesState !== lastUiPreferencesState) {
    lastUiPreferencesState = nextUiPreferencesState;
    persistUiPreferences(nextUiPreferencesState);
  }
});

export type ReduxRootState = ReturnType<typeof store.getState>;
export type ReduxAppDispatch = typeof store.dispatch;

export const useAppDispatch: () => ReduxAppDispatch = useDispatch;
export const useAppSelector: TypedUseSelectorHook<ReduxRootState> = useSelector;

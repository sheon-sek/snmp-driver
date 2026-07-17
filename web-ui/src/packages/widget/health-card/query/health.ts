import { healthApi } from "../api";
import { createUseHealth } from "@/lib/query/health";

const healths = createUseHealth(healthApi);

export const { useHealth } = healths;

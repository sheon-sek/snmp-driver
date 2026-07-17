import { createBaseApi } from "@/lib/api";
import { injectHealthEndpoints } from "@/lib/api/health";

export const baseApi = createBaseApi({
  reducerPath: "healthCardApi",
});

export const healthApi = injectHealthEndpoints(baseApi);

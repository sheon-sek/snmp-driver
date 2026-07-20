import { createRoot } from "react-dom/client";

import LicensePage from "@/packages/page/license";

const rootElement = document.getElementById("root");
if (!rootElement) {
  throw new Error("Missing #root element");
}

createRoot(rootElement).render(<LicensePage />);

export default LicensePage;

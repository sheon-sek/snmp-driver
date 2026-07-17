import { createRoot } from "react-dom/client";

import WebApp from "@/packages/webapp/WebApp";

const rootElement = document.getElementById("root");
if (!rootElement) {
  throw new Error("Missing #root element");
}

createRoot(rootElement).render(<WebApp />);

export default WebApp;

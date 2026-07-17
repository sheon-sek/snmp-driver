import {
  ComponentMeta,
  ComponentRegistry,
} from "@inductiveautomation/perspective-client";
import {
  ExampleWebAppComponent,
  ExampleWebAppComponentMeta,
} from "./example-webapp-wrapper";

// Export components for direct host access when needed.
export { ExampleWebAppComponent };

// Add new component metadata here and register each component exactly once.
const components: ComponentMeta[] = [new ExampleWebAppComponentMeta()];

components.forEach((component) => ComponentRegistry.register(component));

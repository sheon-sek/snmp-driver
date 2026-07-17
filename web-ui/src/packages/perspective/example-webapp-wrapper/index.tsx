import {
  Component,
  ComponentMeta,
  ComponentProps,
  ComponentRegistry,
  PComponent,
  PropertyTree,
  SizeObject,
} from '@inductiveautomation/perspective-client';
import { WebApp } from '@/packages/webapp/WebApp';
import { setEmbedMode, setPerspectiveSessionContext } from '@/embedContext';
import { defaultLanguage } from '@/i18n';

setEmbedMode('perspective');


// The 'key' or 'id' for this component type.  Component must be registered with this EXACT key in the Java side as well
// as on the client side.  In the client, this is done in the index file where we import and register through the
// ComponentRegistry provided by the perspective-client API.
export const COMPONENT_TYPE = "ignitionboilerplate.example-webapp"

// This is the shape of the properties we get from the perspective 'props' property tree.
export interface ExampleWebAppProps {
  apiBase: string;
  locale: string;
  theme: string;
}

export class ExampleWebAppComponent extends Component<
  ComponentProps<ExampleWebAppProps>
> {
  render() {
    const { emit, props } = this.props;
    const client = (this.props.store as any)?.view?.page?.parent;

    setPerspectiveSessionContext({
      sessionId: typeof client?.sessionId === 'string' ? client.sessionId : undefined,
      projectName: typeof client?.projectName === 'string' ? client.projectName : undefined,
    });

    return (
      <div {...emit({ classes: ['eetarp-perspective-root'] })}>
        <WebApp apiBase={props.apiBase} locale={props.locale} theme={props.theme} />
      </div>
    );
  }
}

// This is the actual thing that gets registered with the component registry.
export class ExampleWebAppComponentMeta implements ComponentMeta {
  getComponentType(): string {
    return COMPONENT_TYPE;
  }

  // the class or React Type that this component provides
  getViewComponent(): PComponent {
    return ExampleWebAppComponent as unknown as PComponent;
  }

  getDefaultSize(): SizeObject {
    return { width: 640, height: 420 };
  }

  // Invoked when an update to the PropertyTree has occurred,
  // effectively mapping the state of the tree to component props.
  getPropsReducer(tree: PropertyTree): ExampleWebAppProps {
    return {
      apiBase: tree.readString(
        'apiBase',
        import.meta.env.VITE_API_BASE_URL,
      ),
      locale: tree.readString('locale', defaultLanguage),
      theme: tree.readString('theme', 'Light-Mode'),
    };
  }
}

ComponentRegistry.register(new ExampleWebAppComponentMeta());

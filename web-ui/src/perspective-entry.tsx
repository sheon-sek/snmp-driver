import {
  Component,
  ComponentMeta,
  ComponentProps,
  ComponentRegistry,
  PComponent,
  PropertyTree,
  SizeObject,
} from '@inductiveautomation/perspective-client';
import { IgnitionSdkBoilerplate } from './index';
import { setEmbedMode, setPerspectiveSessionContext } from '@/embedContext';
import { defaultLanguage } from '@/i18n';

setEmbedMode('perspective');

export interface ExampleComponentProps {
  apiBase: string;
  locale: string;
  theme: string;
}

export class ExampleComponent extends Component<
  ComponentProps<ExampleComponentProps>
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
        <IgnitionSdkBoilerplate apiBase={props.apiBase} locale={props.locale} theme={props.theme} />
      </div>
    );
  }
}

export class ExampleComponentMeta implements ComponentMeta {
  getComponentType(): string {
    return import.meta.env.VITE_PERSPECTIVE_COMPONENT_TYPE;
  }

  getViewComponent(): PComponent {
    return ExampleComponent as unknown as PComponent;
  }

  getDefaultSize(): SizeObject {
    return { width: 640, height: 420 };
  }

  getPropsReducer(tree: PropertyTree): ExampleComponentProps {
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

ComponentRegistry.register(new ExampleComponentMeta());

import * as React from 'react';
import { createPortal } from 'react-dom';
import sonnerStyles from 'sonner/dist/styles.css?inline';
import styles from '@/global.css?inline';
import vaulStyles from '../../../node_modules/vaul/style.css?inline';

const GLOBAL_CSS_PRIMITIVES_STYLE_ID = 'eetarp-global-css-primitives';

interface ShadowBoundaryContextValue {
  appRootContainer: HTMLElement | null;
  portalContainer: HTMLElement | null;
}

const ShadowBoundaryContext = React.createContext<ShadowBoundaryContextValue>({
  appRootContainer: null,
  portalContainer: null,
});

export function useShadowPortalContainer() {
  return React.useContext(ShadowBoundaryContext).portalContainer;
}
export function useAppRootContainer() {
  return React.useContext(ShadowBoundaryContext).appRootContainer;
}

interface ShadowBoundaryProps {
  children: React.ReactNode;
}

function extractTailwindPropertyRules(css: string) {
  return css.match(/@property\s+--tw-[\w-]+\s*\{[^}]*\}/g)?.join('\n') ?? '';
}

function extractFontFaceRules(css: string) {
  return css.match(/@font-face\s*\{[^}]*\}/g)?.join('\n') ?? '';
}

function ensureGlobalCssPrimitives() {
  if (typeof document === 'undefined' || document.getElementById(GLOBAL_CSS_PRIMITIVES_STYLE_ID)) return;

  const primitiveRules = [
    extractTailwindPropertyRules(styles),
    extractFontFaceRules(styles),
  ].filter(Boolean).join('\n');
  if (!primitiveRules) return;

  const style = document.createElement('style');
  style.id = GLOBAL_CSS_PRIMITIVES_STYLE_ID;
  style.textContent = primitiveRules;
  document.head.appendChild(style);
}

export function ShadowBoundary({ children }: ShadowBoundaryProps) {
  const hostRef = React.useRef<HTMLDivElement>(null);
  const [mountNode, setMountNode] = React.useState<HTMLElement | null>(null);
  const [portalNode, setPortalNode] = React.useState<HTMLElement | null>(null);

  React.useLayoutEffect(() => {
    const host = hostRef.current;
    if (!host) return;

    ensureGlobalCssPrimitives();

    const shadowRoot = host.shadowRoot ?? host.attachShadow({ mode: 'open' });

    let style = shadowRoot.querySelector<HTMLStyleElement>('style[data-eetarp-styles]');
    const shadowStyles = `${styles}\n${sonnerStyles}\n${vaulStyles}`;
    if (!style) {
      style = document.createElement('style');
      style.dataset.eetarpStyles = '';
      shadowRoot.appendChild(style);
    }
    if (style.textContent !== shadowStyles) {
      style.textContent = shadowStyles;
    }

    let appRoot = shadowRoot.querySelector<HTMLElement>('[data-eetarp-shadow-app]');
    if (!appRoot) {
      appRoot = document.createElement('div');
      appRoot.dataset.eetarpShadowApp = '';
      shadowRoot.appendChild(appRoot);
    }

    let nextPortalNode = shadowRoot.querySelector<HTMLElement>('[data-eetarp-portal-root]');
    if (!nextPortalNode) {
      nextPortalNode = document.createElement('div');
      nextPortalNode.dataset.eetarpPortalRoot = '';
    }
    if (nextPortalNode.parentElement !== appRoot) {
      appRoot.appendChild(nextPortalNode);
    }

    setMountNode(appRoot);
    setPortalNode(nextPortalNode);
  }, []);

  const content = mountNode
    ? createPortal(
        <ShadowBoundaryContext.Provider value={{ appRootContainer: mountNode, portalContainer: portalNode }}>
          {children}
        </ShadowBoundaryContext.Provider>,
        mountNode,
      )
    : null;

  return (
    <>
      <div data-eetarp-shadow-host ref={hostRef} />
      {content as unknown as React.ReactNode}
    </>
  );
}

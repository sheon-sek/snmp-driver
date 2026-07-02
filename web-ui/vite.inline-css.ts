import type { Plugin } from "vite";

export function inlineCssIntoUmd(
  entryFilename: string,
  styleId: string,
): Plugin {
  return {
    name: `inline-css-${styleId}`,
    enforce: "post",
    generateBundle(_, bundle) {
      const entryChunk = bundle[entryFilename];
      if (!entryChunk || entryChunk.type !== "chunk") return;

      const cssAssets = Object.entries(bundle).filter(
        ([fileName, asset]) =>
          fileName.endsWith(".css") && asset.type === "asset",
      );
      const css = cssAssets
        .map(([, asset]) =>
          asset.type === "asset" ? String(asset.source) : "",
        )
        .join("\n");
      const processShim = [
        "(function(){",
        'var root=typeof globalThis!=="undefined"?globalThis:self;',
        'if(typeof root.process==="undefined"){',
        'root.process={env:{NODE_ENV:"production"},browser:true};',
        "}",
        "})();",
      ].join("");
      const injection = [
        "(function(){",
        'if(typeof document==="undefined")return;',
        `var id=${JSON.stringify(styleId)};`,
        "if(document.getElementById(id))return;",
        'var style=document.createElement("style");',
        "style.id=id;",
        `style.textContent=${JSON.stringify(css)};`,
        "document.head.appendChild(style);",
        "})();",
      ].join("");

      entryChunk.code = injection + processShim + entryChunk.code;
      for (const [fileName] of cssAssets) {
        delete bundle[fileName];
      }
    },
  };
}

"use client";

import * as React from "react";
import { Check, Copy } from "lucide-react";
import { codeToHtml } from "shiki";

import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { copyTextToClipboard } from "../clipboard";
import { useTranslation } from "react-i18next";

type SupportedLanguage = "json" | "html";

type CodeSnippetProps = {
  code: string | Record<string, unknown> | unknown[];
  language?: SupportedLanguage;
  title?: string;
  className?: string;
};

function formatCode(
  code: CodeSnippetProps["code"],
  language: SupportedLanguage
) {
  if (language === "json") {
    if (typeof code === "string") {
      try {
        return JSON.stringify(JSON.parse(code), null, 2);
      } catch {
        return code;
      }
    }

    return JSON.stringify(code, null, 2);
  }

  if (typeof code === "string") {
    return code;
  }

  return JSON.stringify(code, null, 2);
}

function escapeHtml(value: string) {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

export function CodeSnippet({
  code,
  language = "json",
  title,
  className,
}: CodeSnippetProps) {
  const [copied, setCopied] = React.useState(false);
  const [highlightedHtml, setHighlightedHtml] = React.useState<string>("");
  const { t } = useTranslation();

  const formattedCode = React.useMemo(() => {
    return formatCode(code, language);
  }, [code, language]);

  React.useEffect(() => {
    let mounted = true;

    async function highlightCode() {
      try {
        const html = await codeToHtml(formattedCode, {
          lang: language,
          themes: {
            light: "github-light",
            dark: "github-dark",
          },
          defaultColor: false
        });

        if (mounted) {
          setHighlightedHtml(html);
        }
      } catch {
        if (mounted) {
          setHighlightedHtml(
            `<pre><code>${escapeHtml(formattedCode)}</code></pre>`
          );
        }
      }
    }

    highlightCode();

    return () => {
      mounted = false;
    };
  }, [formattedCode, language]);

  function handleCopy() {
    copyTextToClipboard(formattedCode)
      .then(() => {
        setCopied(true);

        window.setTimeout(() => {
            setCopied(false);
        }, 1500);
      })
      .catch(() => {
          setCopied(false);
      });
  }

  return (
    <div
      className={cn(
        "overflow-hidden rounded-xl border bg-background shadow-sm",
        className
      )}
    >
      <div className="flex items-center justify-between border-b bg-muted/50 px-4 py-2">
        <div className="flex min-w-0 items-center gap-2">
          {title ? (
            <span className="truncate text-sm font-medium text-foreground">
              {title}
            </span>
          ) : null}

          <span className="rounded-md border bg-background px-2 py-0.5 text-xs font-medium text-muted-foreground">
            {language}
          </span>
        </div>

        <Button
          type="button"
          variant="ghost"
          size="icon"
          onClick={handleCopy}
          disabled={copied}
          className="h-8 w-8 text-muted-foreground hover:text-foreground"
          aria-label={t("general.controls.copyCode")}
        >
          {copied ? (
            <Check className="h-4 w-4" />
          ) : (
            <Copy className="h-4 w-4" />
          )}
        </Button>
      </div>

      {highlightedHtml ? (
        <div
          className={cn(
            "max-h-[520px] overflow-auto text-sm",
            "[&_pre]:m-0 [&_pre]:overflow-auto [&_pre]:p-4",
            "[&_code]:font-mono [&_code]:text-[13px] [&_code]:leading-relaxed"
          )}
          dangerouslySetInnerHTML={{ __html: highlightedHtml }}
        />
      ) : (
        <pre className="max-h-[520px] overflow-auto bg-muted p-4 text-sm">
          <code className="font-mono text-[13px] leading-relaxed bg-current">
            {formattedCode}
          </code>
        </pre>
      )}
    </div>
  );
}

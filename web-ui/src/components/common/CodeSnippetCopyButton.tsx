"use client";

import * as React from "react";
import { Check, Copy } from "lucide-react";
import { useTranslation } from "react-i18next";

import { Button } from "@/components/ui/button";

type CodeSnippetCopyButtonProps = {
  code: string;
};

export function CodeSnippetCopyButton({ code }: CodeSnippetCopyButtonProps) {
  const [copied, setCopied] = React.useState(false);
  const { t } = useTranslation();

  async function handleCopy() {
    try {
      await navigator.clipboard.writeText(code);
      setCopied(true);

      window.setTimeout(() => {
        setCopied(false);
      }, 1500);
    } catch {
      setCopied(false);
    }
  }

  return (
    <Button
      type="button"
      variant="ghost"
      size="icon"
      onClick={handleCopy}
      className="h-8 w-8 text-muted-foreground hover:text-foreground"
      aria-label={t("general.controls.copyCode")}
    >
      {copied ? <Check className="h-4 w-4" /> : <Copy className="h-4 w-4" />}
    </Button>
  );
}

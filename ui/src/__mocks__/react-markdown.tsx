import React from "react";

export default function ReactMarkdown({ children }: { children?: React.ReactNode }) {
  if (typeof children !== "string") {
    return <div data-testid="markdown-content">{children}</div>;
  }

  // Parse [link](url)
  const linkRegex = /\[([^\]]+)\]\(([^)]+)\)/g;
  const parts: React.ReactNode[] = [];
  let lastIndex = 0;
  let match: RegExpExecArray | null;

  while ((match = linkRegex.exec(children)) !== null) {
    if (match.index > lastIndex) {
      parts.push(children.substring(lastIndex, match.index));
    }
    const label = match[1];
    const url = match[2];
    if (url && url.startsWith("javascript:")) {
      // Stripped by sanitize
      parts.push(label);
    } else {
      parts.push(
        <a key={match.index} href={url} target="_blank" rel="noopener noreferrer">
          {label}
        </a>
      );
    }
    lastIndex = match.index + match[0].length;
  }

  if (lastIndex < children.length) {
    parts.push(children.substring(lastIndex));
  }

  return <div data-testid="markdown-content">{parts}</div>;
}


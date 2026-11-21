import type { AnchorHTMLAttributes } from "react";
import ReactMarkdown from "react-markdown";
import rehypeSanitize from "rehype-sanitize";
import remarkGfm from "remark-gfm";

const SAFE_PROTOCOLS = new Set(["http:", "https:", "mailto:"]);

export function sanitizeLinkHref(href: string | undefined): string | undefined {
  if (!href) {
    return undefined;
  }
  const trimmed = href.trim();
  if (!trimmed) {
    return undefined;
  }
  if (trimmed.startsWith("/") || trimmed.startsWith("#")) {
    return trimmed;
  }
  try {
    const parsed = new URL(trimmed);
    return SAFE_PROTOCOLS.has(parsed.protocol.toLowerCase()) ? trimmed : undefined;
  } catch {
    return undefined;
  }
}

function SafeAnchor({ href, children, ...rest }: AnchorHTMLAttributes<HTMLAnchorElement>) {
  const safeHref = sanitizeLinkHref(href);
  if (!safeHref) {
    return <span>{children}</span>;
  }
  const isExternal = /^https?:/i.test(safeHref);
  return (
    <a href={safeHref} {...(isExternal ? { target: "_blank", rel: "noopener noreferrer" } : {})} {...rest}>
      {children}
    </a>
  );
}

export function AnnouncementMarkdown({ content, className }: { content: string; className?: string }) {
  return (
    <div className={className ? `announcement-markdown ${className}` : "announcement-markdown"}>
      {/* Never enable rehype-raw here; raw HTML and unsafe URIs are stripped. */}
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        rehypePlugins={[rehypeSanitize]}
        components={{
          a: SafeAnchor,
        }}
      >
        {content}
      </ReactMarkdown>
    </div>
  );
}

import * as React from "react";
import { cn } from "@/lib/utils";

type Variant = "default" | "brand" | "success" | "warning" | "outline";

const variantClasses: Record<Variant, string> = {
  default: "bg-surface-2 text-text",
  brand: "bg-brand-soft text-brand",
  success: "bg-success-soft text-success",
  warning: "bg-warning-soft text-warning",
  outline: "border border-border text-text-muted",
};

export function Badge({
  className,
  variant = "default",
  ...props
}: React.HTMLAttributes<HTMLSpanElement> & { variant?: Variant }) {
  return (
    <span
      className={cn(
        "inline-flex items-center gap-1 rounded-chip px-2 py-0.5 text-xs font-medium",
        variantClasses[variant],
        className,
      )}
      {...props}
    />
  );
}

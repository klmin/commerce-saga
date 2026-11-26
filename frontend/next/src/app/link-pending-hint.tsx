"use client";

import { useLinkStatus } from "next/link";

import { cn } from "@/lib/utils";

/**
 * 누른 링크 아래에 얇은 막대를 띄워 "눌렸다"를 바로 보여 준다. 미리 받아 둔(prefetch) 경로면 pending 이 없어 안 보인다.
 * 부모 Link 에 relative 가 있어야 한다. 늘 그려 두고 opacity 만 바꿔 레이아웃이 흔들리지 않게 한다
 */
export function LinkPendingHint() {
  const { pending } = useLinkStatus();
  return (
    <span
      aria-hidden
      className={cn(
        "pointer-events-none absolute inset-x-2 bottom-0.5 h-0.5 rounded-full bg-current opacity-0 transition-opacity",
        pending && "animate-pulse opacity-60 delay-100",
      )}
    />
  );
}

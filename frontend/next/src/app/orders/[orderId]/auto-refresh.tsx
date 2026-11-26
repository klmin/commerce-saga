"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";

import { Button } from "@/components/ui/button";

const INTERVAL_MS = 1_000;
// 결제 타임아웃·재전송이 겹치면 Saga 가 수십 초 걸린다. 그보다 길면 손으로 새로고침하게 멈춘다
const MAX_MS = 120_000;

/** Saga 가 끝날 때까지 서버 컴포넌트를 다시 그려 진행 상황을 보여 준다 (서버가 Gateway 를 다시 부른다) */
export function AutoRefresh({ active }: { active: boolean }) {
  const router = useRouter();
  const [stopped, setStopped] = useState(false);

  useEffect(() => {
    if (!active || stopped) {
      return;
    }
    const startedAt = Date.now();
    const timer = setInterval(() => {
      if (Date.now() - startedAt > MAX_MS) {
        setStopped(true);
        return;
      }
      router.refresh();
    }, INTERVAL_MS);
    return () => clearInterval(timer);
  }, [active, stopped, router]);

  if (!active) {
    return null;
  }
  if (stopped) {
    return (
      <Button variant="outline" size="sm" onClick={() => setStopped(false)}>
        다시 확인
      </Button>
    );
  }
  return (
    <p role="status" className="text-sm text-muted-foreground">
      진행 중... 자동으로 갱신합니다
    </p>
  );
}

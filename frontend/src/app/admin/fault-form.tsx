"use client";

import { type FormEvent, startTransition, useActionState, useId, useState } from "react";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  FAULT_MODE_LABEL,
  FAULT_MODES,
  type FaultMode,
  type FaultSettings,
  MAX_DELAY_MILLIS,
} from "@/lib/admin/fault-input";

import type { FaultFormState } from "./admin-actions";

type Props = {
  current: FaultSettings;
  action: (prev: FaultFormState, formData: FormData) => Promise<FaultFormState>;
};

const initialState: FaultFormState = {};

// 입력은 제어 컴포넌트로 두고, 저장 뒤에도 고른 값이 그대로 보이게 한다(저장한 값 = 화면 값)
export function FaultForm({ current, action }: Props) {
  const [state, formAction, pending] = useActionState(action, initialState);
  const [mode, setMode] = useState<FaultMode>(current.mode);
  const [delay, setDelay] = useState(String(current.delayMillis));
  const id = useId();

  function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending) {
      return;
    }
    const formData = new FormData(event.currentTarget);
    startTransition(() => formAction(formData));
  }

  return (
    <form
      // 하이드레이션 전 제출도 Server Action 으로. 붙은 뒤에는 onSubmit 이 직접 불러 React 의 폼 초기화를 피한다
      action={formAction}
      onSubmit={onSubmit}
      className="flex flex-col gap-4"
    >
      <fieldset className="flex flex-col gap-2">
        <legend className="mb-1 text-sm font-medium">결제 결과</legend>
        {FAULT_MODES.map((m) => (
          <label key={m} className="flex items-center gap-2 text-sm">
            <input
              type="radio"
              name="mode"
              value={m}
              checked={mode === m}
              onChange={() => setMode(m)}
              required
            />
            {FAULT_MODE_LABEL[m]} <code className="text-muted-foreground">{m}</code>
          </label>
        ))}
      </fieldset>
      <div className="flex flex-col gap-2">
        <Label htmlFor={`${id}-delay`}>지연 시간 (ms, 지연 후 승인일 때만)</Label>
        <Input
          id={`${id}-delay`}
          name="delayMillis"
          type="number"
          min={0}
          max={MAX_DELAY_MILLIS}
          step={1}
          value={delay}
          onChange={(e) => setDelay(e.target.value)}
          disabled={mode !== "DELAY"}
          className="w-40"
        />
        <p className="text-xs text-muted-foreground">
          payment-service 는 PG 응답을 3초까지 기다린다. 그보다 길면 결과를 모르는 결제(UNKNOWN)가
          되어 PG 조회로 확정하고, Saga 가 결제 단계를 포기하면 결제 취소·보상 후 늦은 승인을 자동
          환불한다 (시나리오 2)
        </p>
      </div>
      {state.error && (
        <p role="alert" className="text-sm text-destructive">
          {state.error}
        </p>
      )}
      {state.saved && !state.error && (
        <p role="status" className="text-sm text-emerald-700 dark:text-emerald-400">
          저장했습니다: {FAULT_MODE_LABEL[state.saved.mode]}
          {state.saved.mode === "DELAY" && ` ${state.saved.delayMillis.toLocaleString("ko-KR")}ms`}
        </p>
      )}
      <Button type="submit" disabled={pending} className="self-start">
        {pending ? "저장 중..." : "저장"}
      </Button>
    </form>
  );
}

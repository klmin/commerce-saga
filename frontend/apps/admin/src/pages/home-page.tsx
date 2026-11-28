import { Button } from "@commerce/ui/button";
import { Input } from "@commerce/ui/input";
import { Label } from "@commerce/ui/label";
import { LoadError, Page, Section } from "@commerce/ui/page";
import { FormSkeleton } from "@commerce/ui/skeletons";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { type FormEvent, useId, useState } from "react";
import { Link } from "react-router";

import { getPaymentFaults, setPaymentFaults } from "@/api/admin";
import {
  FAULT_MODE_LABEL,
  FAULT_MODES,
  type FaultMode,
  type FaultSettings,
  MAX_DELAY_MILLIS,
  parseFaultInput,
} from "@/lib/fault-input";
import { failureMessage } from "@/lib/format";

/**
 * 입력은 제어 컴포넌트로 두고, 저장 뒤에도 고른 값이 그대로 보이게 한다(저장한 값 = 화면 값).
 * 서버 값이 바뀌면(다시 조회, 다른 관리자의 변경) 부모가 key 로 다시 마운트해 그 값으로 채운다.
 * 저장 결과 문구는 다시 마운트돼도 남게 부모가 들고 있다
 */
function FaultForm({
  current,
  saved,
  onSaved,
}: {
  current: FaultSettings;
  saved?: FaultSettings;
  onSaved: (settings: FaultSettings | undefined) => void;
}) {
  const queryClient = useQueryClient();
  const [mode, setMode] = useState<FaultMode>(current.mode);
  const [delay, setDelay] = useState(String(current.delayMillis));
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string>();
  const id = useId();

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending) {
      return;
    }
    const input = parseFaultInput(new FormData(event.currentTarget));
    if (!input.ok) {
      setError(input.error);
      return;
    }
    setPending(true);
    setError(undefined);
    try {
      const result = await setPaymentFaults(input.value);
      onSaved(result);
      queryClient.setQueryData(["payment-faults"], result);
    } catch (e) {
      onSaved(undefined);
      setError(failureMessage(e));
    } finally {
      setPending(false);
    }
  }

  return (
    <form onSubmit={onSubmit} className="flex flex-col gap-4">
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
      {error && (
        <p role="alert" className="text-sm text-destructive">
          {error}
        </p>
      )}
      {saved && !error && (
        <p role="status" className="text-sm text-emerald-700 dark:text-emerald-400">
          저장했습니다: {FAULT_MODE_LABEL[saved.mode]}
          {saved.mode === "DELAY" && ` ${saved.delayMillis.toLocaleString("ko-KR")}ms`}
        </p>
      )}
      <Button type="submit" disabled={pending} className="self-start">
        {pending ? "저장 중..." : "저장"}
      </Button>
    </form>
  );
}

function PaymentFaults() {
  const faults = useQuery({ queryKey: ["payment-faults"], queryFn: getPaymentFaults });
  const [saved, setSaved] = useState<FaultSettings>();
  if (faults.isPending) {
    return <FormSkeleton fields={1} />;
  }
  if (faults.isError) {
    return <LoadError error={faults.error} retry={() => faults.refetch()} />;
  }
  return (
    <FaultForm
      key={`${faults.data.mode}:${faults.data.delayMillis}`}
      current={faults.data}
      saved={saved}
      onSaved={setSaved}
    />
  );
}

export function HomePage() {
  return (
    <Page>
      <Section title="상품 관리">
        <p className="text-sm text-muted-foreground">
          상품 등록·이름·설명·카테고리·대표 이미지·가격·판매 상태를 바꾸고, 상품마다
          누가·언제·무엇을 바꿨는지 관리 이력을 본다.
        </p>
        <Link to="/products" className="self-start text-sm underline underline-offset-4">
          상품 관리로 가기
        </Link>
      </Section>
      <Section title="결제 장애 주입">
        <p className="text-sm text-muted-foreground">
          가짜 PG 의 결과를 바꿔 결제 실패 보상(포인트 환불 → 재고 해제 → 취소)과 결제 지연을
          화면에서 재현한다. 모든 사용자의 주문에 적용되니 확인한 뒤 &quot;정상 승인&quot;으로
          되돌린다.
        </p>
        <PaymentFaults />
      </Section>
      <Section title="DLT (처리하지 못한 메시지)">
        <p className="text-sm text-muted-foreground">
          재시도해도 처리할 수 없는 메시지는 각 토픽의 <code>&lt;토픽&gt;.DLT</code> 에 원본 그대로
          보관된다(ADR 0012). 화면 대신 운영 도구로 본다.
        </p>
        <ul className="list-disc pl-5 text-sm text-muted-foreground">
          <li>
            조회: Kafka UI <code>http://localhost:8989</code> 의 Topics 에서 <code>*.DLT</code>
          </li>
          <li>
            목록·재처리: <code>scripts/dlt.sh list point.command.DLT</code>,{" "}
            <code>scripts/dlt.sh replay point.command.DLT --offset 1:0 --dry-run</code> (시나리오 6)
          </li>
        </ul>
      </Section>
    </Page>
  );
}

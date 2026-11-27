import { shortId } from "@commerce/bff/short-id";
import { Card, CardContent, CardHeader, CardTitle } from "@commerce/ui/card";
import { Input } from "@commerce/ui/input";
import { Label } from "@commerce/ui/label";
import { LoadError } from "@commerce/ui/page";
import { FormSkeleton } from "@commerce/ui/skeletons";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiError } from "@commerce/bff/api";
import { useId, useState } from "react";

import {
  adjustStock,
  getStock,
  type ProductId,
  STOCK_HISTORY_SIZE,
  stockAdjustments,
} from "@/api/admin";
import { formatTime } from "@/lib/format";
import {
  adjustedMessage,
  MAX_REASON_LENGTH,
  parseStockAdjustInput,
  signedDelta,
  type StockAdjustInput,
} from "@/lib/stock-input";

import { ActionForm, type SubmitResult } from "./product-forms";

/** 보냈지만 결과를 모르는 조정 (요청 키와 그때 보낸 내용) */
type Unsettled = { key: string; input: StockAdjustInput };

/** 서버가 받아들이지 않았다고 확정할 수 있는 응답 (아무것도 반영되지 않았다). 408·429 는 다시 보내 볼 수 있어 뺀다 */
function rejected(e: unknown): boolean {
  return (
    e instanceof ApiError &&
    e.status >= 400 &&
    e.status < 500 &&
    e.status !== 408 &&
    e.status !== 429
  );
}

/**
 * 재고 (inventory-service, ADR 0019). 상품 등록과 별개로 입고해야 주문할 수 있다.
 * 조정 한 번마다 요청 키를 하나 쓴다. 응답을 받지 못하면(시간 초과·연결 끊김·5xx) 반영됐는지 모르므로, 그 키와 내용을
 * 기억해 두고 다음 제출은 폼 값 대신 같은 키·같은 내용으로 다시 보내 확정한다(서버가 처음 결과를 돌려주거나 지금 반영한다).
 * 확정되거나 서버가 거절하면(4xx) 잊고 새 조정을 받는다
 */
export function StockSection({ productId }: { productId: ProductId }) {
  const queryClient = useQueryClient();
  const id = useId();
  const [unsettled, setUnsettled] = useState<Unsettled | null>(null);
  const stock = useQuery({
    queryKey: ["admin-stock", productId],
    queryFn: () => getStock(productId),
  });
  const history = useQuery({
    queryKey: ["admin-stock", productId, "adjustments"],
    queryFn: () => stockAdjustments(productId),
  });

  async function onAdjust(formData: FormData): Promise<SubmitResult> {
    let request = unsettled;
    if (!request) {
      const input = parseStockAdjustInput(formData);
      if (!input.ok) {
        return { error: input.error };
      }
      request = { key: crypto.randomUUID(), input: input.value };
    }
    try {
      const result = await adjustStock(productId, request.input, request.key);
      setUnsettled(null);
      await queryClient.invalidateQueries({ queryKey: ["admin-stock", productId] });
      const message = adjustedMessage(result.adjustment.delta, result.available);
      return { saved: unsettled ? `이전 조정을 확인했습니다. ${message}` : message };
    } catch (e) {
      if (rejected(e)) {
        setUnsettled(null);
        throw e;
      }
      setUnsettled(request);
      return {
        error: `응답을 받지 못해 반영됐는지 모릅니다. 다시 누르면 같은 내용(${signedDelta(request.input.delta)}개)으로 확인합니다.`,
      };
    }
  }

  const error = stock.error ?? history.error;
  if (error && !(stock.isSuccess && history.data)) {
    return (
      <Card>
        <CardHeader>
          <CardTitle>재고</CardTitle>
        </CardHeader>
        <CardContent>
          <LoadError
            error={error}
            retry={() =>
              void queryClient.invalidateQueries({ queryKey: ["admin-stock", productId] })
            }
          />
        </CardContent>
      </Card>
    );
  }
  if (!stock.isSuccess || !history.data) {
    return <FormSkeleton fields={2} />;
  }

  const current = stock.data;
  return (
    <Card>
      <CardHeader>
        <CardTitle>재고</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <p className="text-sm" aria-label="현재 재고">
          {current === null ? (
            <>
              <strong>입고 전</strong>
              <span className="text-muted-foreground">
                {" "}
                (재고가 없어 주문하면 예약 실패로 취소됩니다)
              </span>
            </>
          ) : (
            <>
              주문 가능 <strong>{current.available.toLocaleString("ko-KR")}개</strong>
              <span className="text-muted-foreground">
                {" "}
                · 주문에 잡힌 수량 {current.reserved.toLocaleString("ko-KR")}개
              </span>
            </>
          )}
        </p>
        <ActionForm
          onSubmit={onAdjust}
          submitLabel={unsettled ? "이전 조정 확인" : "재고 조정"}
          label="재고 조정"
          resetOnSuccess
        >
          {unsettled && (
            <p className="text-sm text-amber-700 dark:text-amber-400">
              결과를 모르는 조정이 있습니다: {signedDelta(unsettled.input.delta)}개
              {unsettled.input.reason && ` (${unsettled.input.reason})`}. 이것을 확인한 뒤 새로
              조정할 수 있습니다.
            </p>
          )}
          {/* 결과를 모르는 조정이 있는 동안에는 입력을 막는다 (같은 키로 다른 내용을 보내면 422) */}
          <fieldset disabled={unsettled !== null} className="flex flex-wrap gap-3">
            <div className="flex flex-col gap-1">
              <Label htmlFor={`${id}-delta`}>조정 수량</Label>
              <Input
                id={`${id}-delta`}
                name="delta"
                required
                inputMode="numeric"
                placeholder="입고 10 · 차감 -3"
                className="w-40"
              />
            </div>
            <div className="flex min-w-48 flex-1 flex-col gap-1">
              <Label htmlFor={`${id}-reason`}>사유 (선택)</Label>
              <Input id={`${id}-reason`} name="reason" maxLength={MAX_REASON_LENGTH} />
            </div>
          </fieldset>
          <p className="text-xs text-muted-foreground">
            주문에 잡힌 수량은 뺄 수 없습니다. 주문 가능 수량까지만 차감됩니다.
          </p>
        </ActionForm>
        <div className="flex flex-col gap-2">
          <h3 className="text-sm font-medium">재고 조정 이력</h3>
          {history.data.items.length === 0 ? (
            <p className="text-sm text-muted-foreground">이력이 없습니다.</p>
          ) : (
            <ol aria-label="재고 조정 이력" className="flex flex-col divide-y text-sm">
              {history.data.items.map((a) => (
                <li key={a.id} className="flex flex-wrap items-baseline gap-x-2 py-2">
                  <strong className={a.delta > 0 ? "" : "text-destructive"}>
                    {signedDelta(a.delta)}개
                  </strong>
                  <span>→ {a.availableAfter.toLocaleString("ko-KR")}개</span>
                  {a.reason && <span>{a.reason}</span>}
                  <span className="text-muted-foreground" title={a.adjustedBy}>
                    {formatTime(a.adjustedAt)} · 관리자 #{shortId(a.adjustedBy)}
                  </span>
                </li>
              ))}
            </ol>
          )}
          {history.data.hasNext && (
            <p className="text-xs text-muted-foreground">최근 {STOCK_HISTORY_SIZE}건만 보입니다.</p>
          )}
        </div>
      </CardContent>
    </Card>
  );
}

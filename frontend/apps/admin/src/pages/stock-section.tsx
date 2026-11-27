import { shortId } from "@commerce/bff/short-id";
import { Card, CardContent, CardHeader, CardTitle } from "@commerce/ui/card";
import { Input } from "@commerce/ui/input";
import { Label } from "@commerce/ui/label";
import { LoadError } from "@commerce/ui/page";
import { FormSkeleton } from "@commerce/ui/skeletons";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiError } from "@commerce/bff/api";
import { useMe } from "@commerce/bff/session";
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

/**
 * 결과가 확정되지 않은 조정은 보내기 전에 이 탭의 sessionStorage 에 상품별로 둔다. 확인 중 세션이 끝나 로그인으로 이동하거나
 * 화면을 나갔다 와도 같은 키로 확인을 이어 가게 한다(새 키로 같은 조정을 다시 보내 두 번 반영되지 않게). 확정되면 지운다
 */
// 요청 키는 관리자별로 유일하다(서버가 (관리자, 키)로 찾는다). 다른 관리자가 같은 탭에 로그인하면 남의 조정을 새로 반영하지 않게 관리자 id 도 넣는다
const pendingStorageKey = (adminId: string, productId: ProductId) =>
  `admin:stock-adjust-pending:${adminId}:${productId}`;

function loadPending(adminId: string, productId: ProductId): Unsettled | null {
  try {
    const raw = sessionStorage.getItem(pendingStorageKey(adminId, productId));
    if (!raw) {
      return null;
    }
    const value: unknown = JSON.parse(raw);
    if (
      typeof value === "object" &&
      value !== null &&
      "key" in value &&
      typeof value.key === "string" &&
      "input" in value &&
      typeof value.input === "object" &&
      value.input !== null &&
      "delta" in value.input &&
      Number.isInteger(value.input.delta) &&
      "reason" in value.input &&
      (value.input.reason === null || typeof value.input.reason === "string")
    ) {
      return { key: value.key, input: value.input as StockAdjustInput };
    }
  } catch {
    // 저장소를 쓸 수 없으면(사생활 보호 모드 등) 이 화면 안에서만 기억한다
  }
  return null;
}

function savePending(adminId: string, productId: ProductId, pending: Unsettled | null): void {
  try {
    if (pending) {
      sessionStorage.setItem(pendingStorageKey(adminId, productId), JSON.stringify(pending));
    } else {
      sessionStorage.removeItem(pendingStorageKey(adminId, productId));
    }
  } catch {
    // 위와 같다
  }
}

/**
 * 새 조정이 거절됨(4xx): 이 요청은 반영되지 않았다(Gateway 가 막았거나 서비스가 롤백). 408·429 는 다시 보내 볼 수 있어 뺀다
 */
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
 * 결과를 모르는 조정을 다시 보냈을 때, 처음 요청이 반영되지 않았다고 확정할 수 있는 응답.
 * 409 는 inventory 가 행 락 뒤 그 키의 기록이 없음을 보고 새로 반영하려다 거절한 것이라 처음 요청은 없던 것이다.
 * 그 밖의 거절(403 CSRF 등 Gateway 가 서비스 앞에서 막은 것)은 처음 요청에 대해 아무것도 알려 주지 않으므로 키를 계속 쥔다
 */
function provesNotApplied(e: unknown): boolean {
  return e instanceof ApiError && e.status === 409;
}

/**
 * 재고 (inventory-service, ADR 0019). 상품 등록과 별개로 입고해야 주문할 수 있다.
 * 조정 한 번마다 요청 키를 하나 쓴다. 응답을 받지 못하면(시간 초과·연결 끊김·5xx) 반영됐는지 모르므로, 그 키와 내용을
 * 기억해 두고 다음 제출은 폼 값 대신 같은 키·같은 내용으로 다시 보내 확정한다(서버가 처음 결과를 돌려주거나 지금 반영한다).
 * 확정되면(성공, 또는 다시 보낸 요청이 409 = 처음 요청이 없던 것) 잊고 새 조정을 받는다. 새 조정이 4xx 로 거절되면 바로 잊는다
 */
export function StockSection({ productId }: { productId: ProductId }) {
  // SessionGate 가 /api/me 를 읽은 뒤에 그리므로 보통 바로 있다. 관리자가 바뀌면 key 로 새로 그려 그 관리자의 기록만 읽는다
  const me = useMe();
  if (!me.data) {
    return <FormSkeleton fields={2} />;
  }
  return <StockCard key={me.data.userId} adminId={me.data.userId} productId={productId} />;
}

function StockCard({ adminId, productId }: { adminId: string; productId: ProductId }) {
  const queryClient = useQueryClient();
  const id = useId();
  // 재로그인·재진입 뒤에도 확인 중이던 조정을 이어 간다 (상품마다 key 로 다시 그리므로 처음 한 번 읽으면 된다)
  const [unsettled, setUnsettledState] = useState<Unsettled | null>(() =>
    loadPending(adminId, productId),
  );
  function setUnsettled(pending: Unsettled | null) {
    savePending(adminId, productId, pending);
    setUnsettledState(pending);
  }
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
      // 보내기 전에 남긴다: 응답 전에 로그인으로 이동(401)하거나 탭이 닫혀도 이 키로 확인할 수 있게
      savePending(adminId, productId, request);
    }
    try {
      const result = await adjustStock(productId, request.input, request.key);
      setUnsettled(null);
      await queryClient.invalidateQueries({ queryKey: ["admin-stock", productId] });
      const message = adjustedMessage(result.adjustment.delta, result.available);
      return { saved: unsettled ? `이전 조정을 확인했습니다. ${message}` : message };
    } catch (e) {
      const retrying = unsettled !== null;
      if (retrying ? provesNotApplied(e) : rejected(e)) {
        setUnsettled(null);
        throw e;
      }
      setUnsettled(request);
      const reason = e instanceof ApiError ? `(${e.message}) ` : "";
      return {
        error: `${retrying ? "이전 조정을 확인하지 못했습니다" : "응답을 받지 못해 반영됐는지 모릅니다"}. ${reason}다시 누르면 같은 내용(${signedDelta(request.input.delta)}개)으로 확인합니다.`,
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

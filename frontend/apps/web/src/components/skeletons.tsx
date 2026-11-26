import type { ReactNode } from "react";

import { Skeleton } from "@commerce/ui/skeleton";

/**
 * 불러오는 동안 보여 줄 틀. 화면 읽기 프로그램에는 "불러오는 중..." 을 알리고,
 * 눈에는 200ms 뒤에야 보인다(.delayed-reveal). 빨리 끝나는 요청에서 깜빡이지 않게 한다
 */
function Pending({ children }: { children: ReactNode }) {
  return (
    <div role="status" aria-live="polite">
      <span className="sr-only">불러오는 중...</span>
      <div aria-hidden className="delayed-reveal">
        {children}
      </div>
    </div>
  );
}

/** 제목 한 줄 + 설명 한 줄 + 오른쪽 배지인 목록(주문·포인트·결제·알림·기기). 실제 행과 높이를 맞춘다 */
export function ListSkeleton({ rows = 3 }: { rows?: number }) {
  return (
    <Pending>
      <ul className="flex flex-col divide-y">
        {Array.from({ length: rows }, (_, i) => (
          <li key={i} className="flex items-center gap-4 py-3">
            <div className="flex flex-1 flex-col gap-1">
              <Skeleton className="my-0.5 h-5 w-40" />
              <Skeleton className="h-4 w-64 max-w-full" />
            </div>
            <Skeleton className="h-6 w-16 rounded-full" />
          </li>
        ))}
      </ul>
    </Pending>
  );
}

/** 잔액 같은 큰 숫자 하나 (text-2xl) */
export function ValueSkeleton() {
  return (
    <Pending>
      <Skeleton className="h-8 w-28" />
    </Pending>
  );
}

/** 입력 칸 몇 개 + 버튼인 폼 */
export function FormSkeleton({ fields = 2 }: { fields?: number }) {
  return (
    <Pending>
      <div className="flex flex-col gap-4">
        {Array.from({ length: fields }, (_, i) => (
          <div key={i} className="flex flex-col gap-2">
            <Skeleton className="h-4 w-20" />
            <Skeleton className="h-9 w-full" />
          </div>
        ))}
        <Skeleton className="h-9 w-24" />
      </div>
    </Pending>
  );
}

/** 주문 상세: 주문 카드 + Saga 카드 */
export function OrderDetailSkeleton() {
  return (
    <Pending>
      <div className="flex flex-col gap-6">
        <div className="flex flex-col gap-6 rounded-xl border py-6 shadow-sm">
          <div className="flex items-start justify-between gap-4 px-6">
            <div className="flex flex-col gap-2">
              <Skeleton className="h-5 w-48" />
              <Skeleton className="h-3.5 w-72 max-w-full" />
            </div>
            <Skeleton className="h-6 w-16 rounded-full" />
          </div>
          <div className="flex flex-col gap-2 px-6">
            <Skeleton className="h-4 w-full" />
            <Skeleton className="h-4 w-full" />
            <Skeleton className="h-4 w-2/3" />
          </div>
        </div>
        <div className="flex flex-col gap-6 rounded-xl border py-6 shadow-sm">
          <Skeleton className="mx-6 h-5 w-56" />
          <div className="flex flex-col gap-3 px-6">
            {Array.from({ length: 4 }, (_, i) => (
              <Skeleton key={i} className="h-5 w-full" />
            ))}
          </div>
        </div>
      </div>
    </Pending>
  );
}

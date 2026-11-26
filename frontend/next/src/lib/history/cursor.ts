// 목록 화면의 ?before= 커서. 형식이 틀리면 처음 쪽으로 (백엔드 400 을 화면 오류로 만들지 않는다)

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** 주문·결제·포인트 내역: id 가 UUID */
export function uuidCursor(raw: string | string[] | undefined): string | undefined {
  return typeof raw === "string" && UUID.test(raw) ? raw : undefined;
}

/** 알림: id 가 양의 정수 */
export function numberCursor(raw: string | string[] | undefined): number | undefined {
  if (typeof raw !== "string" || !/^[1-9]\d{0,17}$/.test(raw)) {
    return undefined;
  }
  const n = Number(raw);
  return Number.isSafeInteger(n) ? n : undefined;
}

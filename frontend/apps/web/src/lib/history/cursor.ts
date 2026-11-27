// 목록 화면의 ?before= 커서. 형식이 틀리면 처음 쪽으로 (백엔드 400 을 화면 오류로 만들지 않는다)

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** 주문·결제·포인트 내역·알림: id 가 UUID (알림은 UUIDv7, ADR 0018) */
export function uuidCursor(raw: string | string[] | undefined): string | undefined {
  return typeof raw === "string" && UUID.test(raw) ? raw : undefined;
}

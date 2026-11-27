/**
 * 화면에 보일 짧은 id: UUID 의 끝 8자. UUIDv7 은 앞부분이 만든 시각이라 같은 때 만든 것끼리 겹치므로 끝(무작위)을 쓴다 (ADR 0018).
 * 전체 값은 title 등으로 함께 둔다
 */
export function shortId(id: string): string {
  return id.slice(-8);
}

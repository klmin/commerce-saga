// 관리자 상품 폼 → product-service /admin/products 본문, 관리 이력 표시 (후속 4-3b).
// 범위 검사는 백엔드도 한다(@NotBlank @Size @Positive @Max, 도메인). 여기서는 잘못된 값을 보내기 전에 화면에서 알려 준다

/** product-service Product.MAX_NAME_LENGTH·MAX_DESCRIPTION_LENGTH, Money.MAX */
export const MAX_NAME_LENGTH = 100;
export const MAX_DESCRIPTION_LENGTH = 500;
export const MAX_PRICE = 100_000_000;

type Parsed<T> = { ok: true; value: T } | { ok: false; error: string };

export type DescribeInput = { name: string; description: string };

export function parseDescribeInput(formData: FormData): Parsed<DescribeInput> {
  const name = String(formData.get("name") ?? "").trim();
  const description = String(formData.get("description") ?? "").trim();
  if (!name) {
    return { ok: false, error: "상품 이름을 입력하세요." };
  }
  if (name.length > MAX_NAME_LENGTH) {
    return { ok: false, error: `상품 이름은 ${MAX_NAME_LENGTH}자까지입니다.` };
  }
  if (description.length > MAX_DESCRIPTION_LENGTH) {
    return { ok: false, error: `설명은 ${MAX_DESCRIPTION_LENGTH}자까지입니다.` };
  }
  return { ok: true, value: { name, description } };
}

export function parsePriceInput(formData: FormData): Parsed<number> {
  const raw = String(formData.get("price") ?? "")
    .trim()
    .replaceAll(",", "");
  if (!/^\d{1,9}$/.test(raw) || Number(raw) < 1 || Number(raw) > MAX_PRICE) {
    return {
      ok: false,
      error: `가격은 1원부터 ${MAX_PRICE.toLocaleString("ko-KR")}원까지 정수로 입력하세요.`,
    };
  }
  return { ok: true, value: Number(raw) };
}

export type RegisterInput = DescribeInput & { price: number };

export function parseRegisterInput(formData: FormData): Parsed<RegisterInput> {
  const describe = parseDescribeInput(formData);
  if (!describe.ok) {
    return describe;
  }
  const price = parsePriceInput(formData);
  if (!price.ok) {
    return price;
  }
  return { ok: true, value: { ...describe.value, price: price.value } };
}

export function parseSaleInput(formData: FormData): Parsed<boolean> {
  const raw = formData.get("onSale");
  if (raw !== "true" && raw !== "false") {
    return { ok: false, error: "판매 상태를 고르세요." };
  }
  return { ok: true, value: raw === "true" };
}

// --- 관리 이력 (GET /admin/products/{id}/audit-log) ---

export const AUDIT_ACTIONS = [
  "REGISTERED",
  "DESCRIBED",
  "PRICE_CHANGED",
  "SALE_STATUS_CHANGED",
] as const;
export type AuditAction = (typeof AUDIT_ACTIONS)[number];

export const AUDIT_ACTION_LABEL: Record<AuditAction, string> = {
  REGISTERED: "등록",
  DESCRIBED: "정보 수정",
  PRICE_CHANGED: "가격 변경",
  SALE_STATUS_CHANGED: "판매 상태 변경",
};

/** 그 시점 값. 관리 이력 이전의 가격 변경(가격 이력에서 옮긴 것)은 가격 외 값이 null */
export type AuditSnapshot = {
  name: string | null;
  description: string | null;
  price: number | null;
  onSale: boolean | null;
};

export type AuditEntry = {
  action: AuditAction;
  /** 등록이면 null */
  before: AuditSnapshot | null;
  after: AuditSnapshot;
  /** 바꾼 관리자 userId (UUID) */
  changedBy: string;
  changedAt: string;
};

export type FieldChange = { label: string; before: string | null; after: string };

const FIELDS: { key: keyof AuditSnapshot; label: string }[] = [
  { key: "name", label: "이름" },
  { key: "description", label: "설명" },
  { key: "price", label: "가격" },
  { key: "onSale", label: "판매 상태" },
];

function show(key: keyof AuditSnapshot, value: string | number | boolean): string {
  if (key === "price") {
    return `${Number(value).toLocaleString("ko-KR")}원`;
  }
  if (key === "onSale") {
    return value ? "판매 중" : "판매 중지";
  }
  return value === "" ? "(없음)" : String(value);
}

/**
 * 이력 한 건에서 바뀐 값만 (전 → 후). 등록은 처음 값 전부(before = null).
 * 값을 모르는 칸(null)은 건너뛴다
 */
export function auditChanges(entry: AuditEntry): FieldChange[] {
  const changes: FieldChange[] = [];
  for (const { key, label } of FIELDS) {
    const after = entry.after[key];
    if (after === null) {
      continue;
    }
    if (entry.before === null) {
      changes.push({ label, before: null, after: show(key, after) });
      continue;
    }
    const before = entry.before[key];
    if (before !== null && before !== after) {
      changes.push({ label, before: show(key, before), after: show(key, after) });
    }
  }
  return changes;
}

// 관리자 상품 폼 → product-service /admin/products 본문, 관리 이력 표시 (후속 4-3b).
// 범위 검사는 백엔드도 한다(@NotBlank @Size @Positive @Max, 도메인). 여기서는 잘못된 값을 보내기 전에 화면에서 알려 준다

/** product-service Product.MAX_NAME_LENGTH·MAX_DESCRIPTION_LENGTH, Money.MAX */
export const MAX_NAME_LENGTH = 100;
export const MAX_DESCRIPTION_LENGTH = 500;
export const MAX_PRICE = 100_000_000;

type Parsed<T> = { ok: true; value: T } | { ok: false; error: string };

/** product-service CategoryCode 모양 (ADR 0021). 있는 코드인지는 서비스가 본다 */
const CATEGORY_CODE = /^[a-z][a-z0-9-]{0,29}$/;

export type DescribeInput = { name: string; description: string; categoryCode: string };

export function parseDescribeInput(formData: FormData): Parsed<DescribeInput> {
  const name = String(formData.get("name") ?? "").trim();
  const description = String(formData.get("description") ?? "").trim();
  const categoryCode = String(formData.get("categoryCode") ?? "");
  if (!CATEGORY_CODE.test(categoryCode)) {
    return { ok: false, error: "카테고리를 고르세요." };
  }
  if (!name) {
    return { ok: false, error: "상품 이름을 입력하세요." };
  }
  if (name.length > MAX_NAME_LENGTH) {
    return { ok: false, error: `상품 이름은 ${MAX_NAME_LENGTH}자까지입니다.` };
  }
  if (description.length > MAX_DESCRIPTION_LENGTH) {
    return { ok: false, error: `설명은 ${MAX_DESCRIPTION_LENGTH}자까지입니다.` };
  }
  return { ok: true, value: { name, description, categoryCode } };
}

// --- 대표 이미지 (POST /admin/products/{id}/image, ADR 0021) ---

/** product-service ImageFormat.MAX_UPLOAD_BYTES */
export const MAX_IMAGE_BYTES = 2 * 1024 * 1024;
/** 올릴 수 있는 형식 (SVG 는 시드 전용이라 올릴 수 없다). 서비스는 내용(매직 바이트)으로 다시 본다 */
export const IMAGE_TYPES = ["image/jpeg", "image/png", "image/webp"] as const;

/** 고른 파일 검사. 고르지 않았거나 형식·크기가 맞지 않으면 화면 문구 */
export function checkImageFile(file: File | null | undefined): Parsed<File> {
  if (!file || file.size === 0) {
    return { ok: false, error: "이미지 파일을 고르세요." };
  }
  if (!(IMAGE_TYPES as readonly string[]).includes(file.type)) {
    return { ok: false, error: "jpg·png·webp 이미지만 올릴 수 있습니다." };
  }
  if (file.size > MAX_IMAGE_BYTES) {
    return { ok: false, error: "이미지는 2MB 까지 올릴 수 있습니다." };
  }
  return { ok: true, value: file };
}

/** 서비스 기준 이미지 경로(/products/{id}/images/{파일}) → 화면이 부를 주소 (같은 출처 Gateway /api) */
export function imageUrl(imagePath: string | null | undefined): string | undefined {
  return imagePath && imagePath.startsWith("/products/") ? `/api${imagePath}` : undefined;
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
  "IMAGE_CHANGED",
] as const;
export type AuditAction = (typeof AUDIT_ACTIONS)[number];

export const AUDIT_ACTION_LABEL: Record<AuditAction, string> = {
  REGISTERED: "등록",
  DESCRIBED: "정보 수정",
  PRICE_CHANGED: "가격 변경",
  SALE_STATUS_CHANGED: "판매 상태 변경",
  IMAGE_CHANGED: "이미지 변경",
};

/** 그 시점 값. 관리 이력 이전의 가격 변경(가격 이력에서 옮긴 것)은 가격 외 값이 null */
export type AuditSnapshot = {
  name: string | null;
  description: string | null;
  price: number | null;
  onSale: boolean | null;
  /** 카테고리 코드 (ADR 0021). 그 열 전의 이력이면 null */
  categoryCode: string | null;
  /** 대표 이미지 읽기 경로. 이미지가 없거나 그 열 전의 이력이면 null */
  imagePath: string | null;
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
  { key: "categoryCode", label: "카테고리" },
];

function show(
  key: keyof AuditSnapshot,
  value: string | number | boolean,
  categoryNames: ReadonlyMap<string, string>,
): string {
  if (key === "price") {
    return `${Number(value).toLocaleString("ko-KR")}원`;
  }
  if (key === "onSale") {
    return value ? "판매 중" : "판매 중지";
  }
  if (key === "categoryCode") {
    return categoryNames.get(String(value)) ?? String(value);
  }
  return value === "" ? "(없음)" : String(value);
}

/** 이미지 경로의 파일 이름 (이력에는 어떤 파일로 바뀌었는지만) */
function imageFileName(imagePath: string | null): string {
  return imagePath === null ? "(없음)" : (imagePath.split("/").pop() ?? imagePath);
}

/**
 * 이력 한 건에서 바뀐 값만 (전 → 후). 등록은 처음 값 전부(before = null).
 * 값을 모르는 칸(null)은 건너뛴다. 이미지는 없던 것(null)에서 바뀐 것도 보인다(이미지 변경 이력).
 * categoryNames 는 카테고리 코드 → 이름 (모르는 코드는 코드 그대로)
 */
export function auditChanges(
  entry: AuditEntry,
  categoryNames: ReadonlyMap<string, string> = new Map(),
): FieldChange[] {
  const changes: FieldChange[] = [];
  for (const { key, label } of FIELDS) {
    const after = entry.after[key];
    if (after === null) {
      continue;
    }
    if (entry.before === null) {
      changes.push({ label, before: null, after: show(key, after, categoryNames) });
      continue;
    }
    const before = entry.before[key];
    if (before !== null && before !== after) {
      changes.push({
        label,
        before: show(key, before, categoryNames),
        after: show(key, after, categoryNames),
      });
    }
  }
  if (entry.action === "IMAGE_CHANGED" && entry.before?.imagePath !== entry.after.imagePath) {
    changes.push({
      label: "이미지",
      before: imageFileName(entry.before?.imagePath ?? null),
      after: imageFileName(entry.after.imagePath),
    });
  }
  return changes;
}

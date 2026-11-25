"use server";

import { refresh } from "next/cache";
import { redirect, unstable_rethrow } from "next/navigation";

import {
  parseDescribeInput,
  parsePriceInput,
  parseRegisterInput,
  parseSaleInput,
} from "@/lib/admin/product-input";
import {
  changeProductPrice,
  changeProductSaleStatus,
  describeProduct,
  registerProduct,
} from "@/lib/api/admin-products";
import { ApiError } from "@/lib/api/client";
import { logError } from "@/lib/log";

export type ProductFormState = { error?: string; saved?: string };

// 권한(ADMIN)은 Gateway·product-service 가 토큰으로 검사하고, 바꾼 사람(JWT sub)은 서버가 관리 이력에 남긴다
function failure(e: unknown, what: string): ProductFormState {
  // 로그인이 풀려 authedRequest 가 던지는 redirect 는 그대로 올린다
  unstable_rethrow(e);
  if (e instanceof ApiError) {
    if (e.status === 403) {
      return { error: "관리자만 바꿀 수 있습니다." };
    }
    if (e.status === 404) {
      return { error: "없는 상품입니다." };
    }
    return { error: e.message };
  }
  logError(`${what} failed`, e);
  return { error: "서버에 연결할 수 없습니다. 잠시 뒤 다시 시도해 주세요." };
}

export async function registerProductAction(
  _prev: ProductFormState,
  formData: FormData,
): Promise<ProductFormState> {
  const input = parseRegisterInput(formData);
  if (!input.ok) {
    return { error: input.error };
  }
  let id: number;
  try {
    id = (await registerProduct(input.value)).id;
  } catch (e) {
    return failure(e, "registerProductAction");
  }
  redirect(`/admin/products/${id}`);
}

export async function describeProductAction(
  productId: number,
  _prev: ProductFormState,
  formData: FormData,
): Promise<ProductFormState> {
  const input = parseDescribeInput(formData);
  if (!input.ok) {
    return { error: input.error };
  }
  try {
    await describeProduct(productId, input.value);
  } catch (e) {
    return failure(e, "describeProductAction");
  }
  refresh();
  return { saved: "이름·설명을 저장했습니다." };
}

export async function changePriceAction(
  productId: number,
  _prev: ProductFormState,
  formData: FormData,
): Promise<ProductFormState> {
  const input = parsePriceInput(formData);
  if (!input.ok) {
    return { error: input.error };
  }
  try {
    await changeProductPrice(productId, input.value);
  } catch (e) {
    return failure(e, "changePriceAction");
  }
  refresh();
  return { saved: `가격을 ${input.value.toLocaleString("ko-KR")}원으로 바꿨습니다.` };
}

export async function changeSaleStatusAction(
  productId: number,
  _prev: ProductFormState,
  formData: FormData,
): Promise<ProductFormState> {
  const input = parseSaleInput(formData);
  if (!input.ok) {
    return { error: input.error };
  }
  try {
    await changeProductSaleStatus(productId, input.value);
  } catch (e) {
    return failure(e, "changeSaleStatusAction");
  }
  refresh();
  return { saved: input.value ? "판매를 다시 시작했습니다." : "판매를 중지했습니다." };
}

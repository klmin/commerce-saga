import { useQuery } from "@tanstack/react-query";

import { listCategories } from "@/api/products";

/** 카테고리 목록 (헤더 메뉴·상품 화면이 같이 쓴다, 한 번 읽고 둔다) */
export function useCategories() {
  return useQuery({ queryKey: ["categories"], queryFn: listCategories, staleTime: Infinity });
}

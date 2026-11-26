/** 라우터 basename: <base href> 의 경로 (호스트 방식·로컬 "/", 경로 방식 "/auth", 설계 문서 7-4) */
export function routerBasename(baseUri: string = document.baseURI): string {
  const path = new URL(baseUri).pathname.replace(/\/+$/, "");
  return path === "" ? "/" : path;
}

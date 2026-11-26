// 이 화면의 앞 경로. index.html 의 <base href> 에서 읽는다(nginx 가 BASE_PATH 로 넣음, 설계 문서 7-4).
// 호스트 방식·로컬 개발은 "/", 경로 방식 관리자 화면이면 "/admin/"

/** "/" 또는 "/admin/" 처럼 / 로 끝나는 앞 경로 */
export function basePath(baseUri: string = document.baseURI): string {
  const path = new URL(baseUri).pathname;
  return path.endsWith("/") ? path : `${path}/`;
}

/** 라우터 basename ("/" 또는 "/admin") */
export function routerBasename(baseUri?: string): string {
  const path = basePath(baseUri);
  return path === "/" ? "/" : path.slice(0, -1);
}

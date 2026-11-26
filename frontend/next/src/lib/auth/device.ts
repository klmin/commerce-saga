// 로그인 기기 목록의 이름. auth-service 는 로그인 때 받은 User-Agent 를 그대로 둔다(인증에는 쓰지 않음).
// 화면에는 브라우저·OS 만 짧게 보인다

const BROWSERS: [RegExp, string][] = [
  [/Edg\//, "Edge"],
  [/OPR\//, "Opera"],
  [/Firefox\//, "Firefox"],
  [/Chrome\//, "Chrome"],
  [/Safari\//, "Safari"],
  [/curl\//, "curl"],
];

const SYSTEMS: [RegExp, string][] = [
  [/Windows/, "Windows"],
  [/Android/, "Android"],
  [/iPhone|iPad/, "iOS"],
  [/Mac OS X|Macintosh/, "macOS"],
  [/Linux/, "Linux"],
];

export function deviceName(agent: string | null | undefined): string {
  if (!agent?.trim()) {
    return "알 수 없는 기기";
  }
  const browser = BROWSERS.find(([re]) => re.test(agent))?.[1];
  const system = SYSTEMS.find(([re]) => re.test(agent))?.[1];
  if (browser && system) {
    return `${browser} · ${system}`;
  }
  return browser ?? system ?? agent.trim().slice(0, 60);
}

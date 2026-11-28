/**
 * 화면 SPA 공통 로고 (사용자·관리자·인증 화면이 같은 모양, 후속 9-6). label 은 로고 옆 배지(예: 관리자).
 * 링크는 부르는 쪽이 감싼다(인증 화면은 링크 없이)
 */
export function Brand({ label }: { label?: string }) {
  return (
    <span className="flex items-center gap-2 text-lg font-bold tracking-tight">
      <span
        aria-hidden="true"
        className="flex size-7 items-center justify-center rounded-md bg-primary text-sm text-primary-foreground"
      >
        cs
      </span>
      commerce-saga
      {label && (
        <span className="rounded-full bg-secondary px-2 py-0.5 text-xs font-medium text-secondary-foreground">
          {label}
        </span>
      )}
    </span>
  );
}

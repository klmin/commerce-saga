package com.klmin.commerce.authservice.auth.application.port.in;

import java.util.List;

public interface ManageSessionsUseCase {

    /** @param rawCurrentRefreshToken 요청한 기기의 쿠키 값 (없으면 null). 어느 것이 이 기기인지 표시하는 데만 쓴다 */
    List<SessionResult> sessions(long userId, String rawCurrentRefreshToken);

    /** 내 기기 하나를 로그아웃시킨다. 다른 사용자의 것이면 없는 것과 같게 404 */
    void revoke(long userId, String sessionId);
}

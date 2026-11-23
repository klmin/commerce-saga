package com.klmin.commerce.authservice.auth.application.port.out;

import com.klmin.commerce.authservice.auth.domain.RefreshFamily;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** refresh token family 저장소 (Redis). family 는 {@code expiresAt} 에 저절로 사라진다 */
public interface RefreshFamilyPort {

    /** family 와 사용자별 목록을 함께 저장한다 (원자적) */
    void create(RefreshFamily family);

    Optional<RefreshFamily> find(String familyId);

    /**
     * 저장된 family 의 현재 secret 이 아직 {@code expected} 의 것일 때만 {@code updated} 로 바꾼다.
     * 같은 토큰으로 동시에 refresh 하면 하나만 성공한다.
     *
     * @return 바꿨으면 true, 그 사이 다른 요청이 먼저 회전했거나 family 가 사라졌으면 false
     */
    boolean replace(RefreshFamily expected, RefreshFamily updated);

    void delete(RefreshFamily family);

    void deleteAll(long userId);

    /** 사용자의 살아 있는 family 들. 끝난 것은 목록에서 정리한다 */
    List<RefreshFamily> findAll(long userId, Instant now);
}

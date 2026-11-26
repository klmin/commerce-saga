package com.klmin.commerce.authservice.auth.application.service;

import com.klmin.commerce.authservice.auth.application.DeviceNotFoundException;
import com.klmin.commerce.authservice.auth.application.port.in.DeviceResult;
import com.klmin.commerce.authservice.auth.application.port.out.AuthorizationRecordPort;
import com.klmin.commerce.authservice.auth.application.port.out.DevicePort;
import com.klmin.commerce.authservice.auth.application.port.out.SsoSessionPort;
import com.klmin.commerce.authservice.common.error.DomainValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeviceServiceTest {

    static final Instant NOW = Instant.parse("2026-11-26T10:00:00Z");
    static final long USER = 7;

    final Map<Long, Map<String, DevicePort.DeviceRecord>> deviceStore = new HashMap<>();
    final Map<String, Instant> liveSessions = new HashMap<>();
    final List<String> deletedSessions = new ArrayList<>();
    /** deviceId → 마지막 토큰 발급 시각 (refresh 가 살아 있는 인가 기록) */
    final Map<String, Instant> liveTokens = new HashMap<>();
    final List<String> revokedDevices = new ArrayList<>();

    DeviceService service;

    @BeforeEach
    void setUp() {
        DevicePort devices = new DevicePort() {
            public void save(long userId, DeviceRecord device) {
                deviceStore.computeIfAbsent(userId, k -> new LinkedHashMap<>()).put(device.deviceId(), device);
            }

            public List<DeviceRecord> findAll(long userId) {
                return new ArrayList<>(deviceStore.getOrDefault(userId, Map.of()).values());
            }

            public void remove(long userId, String deviceId) {
                deviceStore.getOrDefault(userId, new HashMap<>()).remove(deviceId);
            }
        };
        SsoSessionPort sessions = new SsoSessionPort() {
            public Optional<Instant> lastAccessed(String sessionId) {
                return Optional.ofNullable(liveSessions.get(sessionId));
            }

            public void delete(String sessionId) {
                liveSessions.remove(sessionId);
                deletedSessions.add(sessionId);
            }
        };
        AuthorizationRecordPort authorizations = new AuthorizationRecordPort() {
            public int deleteByPrincipal(String principalName) {
                return 0;
            }

            public int deleteByDevice(String principalName, String deviceId) {
                assertThat(principalName).isEqualTo(Long.toString(USER));
                revokedDevices.add(deviceId);
                return liveTokens.remove(deviceId) == null ? 0 : 1;
            }

            public Map<String, Instant> liveDevices(String principalName, Instant now) {
                return Map.copyOf(liveTokens);
            }

            public int deleteExpiredBefore(Instant cutoff) {
                return 0;
            }
        };
        service = new DeviceService(devices, sessions, authorizations, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private String login(String sessionId, String userAgent) {
        liveSessions.put(sessionId, NOW);
        return service.register(USER, sessionId, userAgent);
    }

    @Test
    void 지금_기기가_맨_앞이고_나머지는_최근_활동_순이다() {
        String laptop = login("s-laptop", "Laptop");
        String phone = login("s-phone", "Phone");
        String tablet = login("s-tablet", "Tablet");
        liveSessions.put("s-phone", NOW.plus(Duration.ofMinutes(5)));
        liveTokens.put(tablet, NOW.plus(Duration.ofMinutes(9)));

        List<DeviceResult> devices = service.devices(USER, laptop);

        assertThat(devices).extracting(DeviceResult::id).containsExactly(laptop, tablet, phone);
        assertThat(devices.getFirst().current()).isTrue();
        assertThat(devices.get(1).lastSeenAt()).isEqualTo(NOW.plus(Duration.ofMinutes(9)));
    }

    @Test
    void SSO_세션이_끝나도_refresh_token_이_살아_있으면_보이고_둘_다_끝나면_사라진다() {
        String shopping = login("s-1", "A");
        String gone = login("s-2", "B");
        liveSessions.clear(); // 30분 유휴로 SSO 세션은 둘 다 끝났다
        liveTokens.put(shopping, NOW); // 화면은 refresh 로 계속 로그인돼 있다

        assertThat(service.devices(USER, null)).extracting(DeviceResult::id).containsExactly(shopping);
        assertThat(deviceStore.get(USER)).doesNotContainKey(gone);
    }

    @Test
    void 다른_기기를_로그아웃하면_SSO_세션과_그_기기의_인가_기록을_지운다() {
        String mine = login("s-mine", "A");
        String other = login("s-other", "B");
        liveTokens.put(other, NOW);

        service.logout(USER, other, mine);

        assertThat(deletedSessions).containsExactly("s-other");
        assertThat(revokedDevices).containsExactly(other);
        assertThat(service.devices(USER, mine)).extracting(DeviceResult::id).containsExactly(mine);
    }

    @Test
    void 지금_기기는_기기_화면에서_로그아웃하지_않는다() {
        String mine = login("s-mine", "A");
        assertThatThrownBy(() -> service.logout(USER, mine, mine)).isInstanceOf(DomainValidationException.class);
        assertThat(deletedSessions).isEmpty();
    }

    @Test
    void 없는_기기나_남의_기기는_404() {
        String mine = login("s-mine", "A");
        String someoneElse = service.register(USER + 1, "s-x", "X");

        assertThatThrownBy(() -> service.logout(USER, someoneElse, mine)).isInstanceOf(DeviceNotFoundException.class);
        assertThat(deletedSessions).isEmpty();
    }

    @Test
    void 다른_기기_모두_로그아웃은_지금_기기만_남긴다() {
        String mine = login("s-mine", "A");
        login("s-2", "B");
        login("s-3", "C");

        assertThat(service.logoutOthers(USER, mine)).isEqualTo(2);
        assertThat(deletedSessions).containsExactlyInAnyOrder("s-2", "s-3");
        assertThat(service.devices(USER, mine)).extracting(DeviceResult::id).containsExactly(mine);
    }

    @Test
    void OIDC_로그아웃한_기기는_인가_기록을_지우고_잊는다() {
        String mine = login("s-mine", "A");
        liveTokens.put(mine, NOW);

        service.loggedOut(USER, mine);

        assertThat(revokedDevices).containsExactly(mine);
        assertThat(deviceStore.get(USER)).isEmpty();
    }

    @Test
    void 브라우저_정보는_제어_문자를_빼고_길이를_자른다() {
        assertThat(DeviceService.userAgent("Mozilla\n5.0\t(X)")).isEqualTo("Mozilla 5.0 (X)");
        assertThat(DeviceService.userAgent("x".repeat(300))).hasSize(DeviceService.USER_AGENT_MAX);
        assertThat(DeviceService.userAgent(null)).isEmpty();
    }
}

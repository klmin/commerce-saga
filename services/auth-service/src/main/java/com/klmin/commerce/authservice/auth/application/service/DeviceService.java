package com.klmin.commerce.authservice.auth.application.service;

import com.klmin.commerce.authservice.auth.application.DeviceNotFoundException;
import com.klmin.commerce.authservice.auth.application.port.in.DeviceResult;
import com.klmin.commerce.authservice.auth.application.port.in.ManageDevicesUseCase;
import com.klmin.commerce.authservice.auth.application.port.out.AuthorizationRecordPort;
import com.klmin.commerce.authservice.auth.application.port.out.DevicePort;
import com.klmin.commerce.authservice.auth.application.port.out.DevicePort.DeviceRecord;
import com.klmin.commerce.authservice.auth.application.port.out.SsoSessionPort;
import com.klmin.commerce.authservice.common.error.DomainValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 로그인 기기 (후속 5-6). 기기 = SSO 세션. 기기의 인가 기록은 SAS 인가 기록의 {@code device_id} 속성으로 찾는다.
 * 기기를 로그아웃하면 그 브라우저는 SSO 세션과 refresh token 을 잃는다. 이미 받은 access token(최대 15분)은 끝날 때까지 쓰이고,
 * 그 뒤 Gateway 의 갱신이 실패해 다시 로그인한다(Gateway 세션은 다른 서비스 것이라 직접 지우지 않는다)
 */
@Service
public class DeviceService implements ManageDevicesUseCase {

    private static final Logger log = LoggerFactory.getLogger(DeviceService.class);
    static final int USER_AGENT_MAX = 200;

    private final DevicePort devices;
    private final SsoSessionPort sessions;
    private final AuthorizationRecordPort authorizations;
    private final Clock clock;

    public DeviceService(DevicePort devices, SsoSessionPort sessions, AuthorizationRecordPort authorizations,
                         Clock clock) {
        this.devices = devices;
        this.sessions = sessions;
        this.authorizations = authorizations;
        this.clock = clock;
    }

    @Override
    public String register(UUID userId, String sessionId, String userAgent) {
        String deviceId = UUID.randomUUID().toString();
        devices.save(userId, new DeviceRecord(deviceId, sessionId, userAgent(userAgent), clock.instant()));
        return deviceId;
    }

    @Override
    public List<DeviceResult> devices(UUID userId, String currentDeviceId) {
        Map<String, Instant> live = authorizations.liveDevices(principal(userId), clock.instant());
        List<DeviceResult> result = new ArrayList<>();
        for (DeviceRecord device : devices.findAll(userId)) {
            Optional<Instant> sessionSeen = sessions.lastAccessed(device.sessionId());
            Instant tokenSeen = live.get(device.deviceId());
            if (sessionSeen.isEmpty() && tokenSeen == null) {
                // SSO 세션도 refresh token 도 끝났다: 그 브라우저는 더 로그인돼 있지 않다
                devices.remove(userId, device.deviceId());
                continue;
            }
            Instant lastSeen = latest(device.loginAt(), sessionSeen.orElse(null), tokenSeen);
            result.add(new DeviceResult(device.deviceId(), device.deviceId().equals(currentDeviceId),
                    device.userAgent(), device.loginAt(), lastSeen));
        }
        result.sort(Comparator.comparing(DeviceResult::current).reversed()
                .thenComparing(DeviceResult::lastSeenAt, Comparator.reverseOrder()));
        return result;
    }

    @Override
    public void logout(UUID userId, String deviceId, String currentDeviceId) {
        if (deviceId.equals(currentDeviceId)) {
            throw new DomainValidationException("지금 기기는 로그아웃 버튼으로 로그아웃합니다");
        }
        DeviceRecord device = devices.findAll(userId).stream()
                .filter(d -> d.deviceId().equals(deviceId))
                .findFirst()
                .orElseThrow(() -> new DeviceNotFoundException(deviceId));
        end(userId, device);
    }

    @Override
    public int logoutOthers(UUID userId, String currentDeviceId) {
        int count = 0;
        for (DeviceRecord device : devices.findAll(userId)) {
            if (!device.deviceId().equals(currentDeviceId)) {
                end(userId, device);
                count++;
            }
        }
        return count;
    }

    @Override
    public void loggedOut(UUID userId, String deviceId) {
        int deleted = authorizations.deleteByDevice(principal(userId), deviceId);
        devices.remove(userId, deviceId);
        log.info("기기 로그아웃(OIDC): 인가 기록 {}건 삭제 (userId={})", deleted, userId);
    }

    @Override
    public void authorized(UUID userId) {
        devices.extend(userId);
    }

    /**
     * 기기 하나를 끝낸다. 트랜잭션으로 묶지 않는다: DB(인가 기록)·Redis(세션·기기 기록)는 함께 롤백되지 않으므로
     * 지우는 순서로 다시 할 수 있게 한다. 인가 기록(바로 커밋) → SSO 세션 → 마지막에 기기 기록.
     * 중간에 실패하면 기기 기록이 남아 다시 로그아웃하면 남은 것을 지운다(이미 지운 것은 0건·없음이라 안전)
     */
    private void end(UUID userId, DeviceRecord device) {
        int deleted = authorizations.deleteByDevice(principal(userId), device.deviceId());
        sessions.delete(device.sessionId());
        devices.remove(userId, device.deviceId());
        log.info("다른 기기 로그아웃: 인가 기록 {}건과 SSO 세션 삭제 (userId={})", deleted, userId);
    }

    private static Instant latest(Instant... instants) {
        Instant max = null;
        for (Instant i : instants) {
            if (i != null && (max == null || i.isAfter(max))) {
                max = i;
            }
        }
        return max;
    }

    private static String principal(UUID userId) {
        return userId.toString(); // SAS principal 이름 = userId 의 UUID 문자열
    }

    /** 화면에 보일 브라우저 정보. 길이를 자르고 제어 문자를 뺀다 */
    static String userAgent(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String clean = raw.replaceAll("\\p{Cntrl}", " ").strip();
        return clean.length() > USER_AGENT_MAX ? clean.substring(0, USER_AGENT_MAX) : clean;
    }
}

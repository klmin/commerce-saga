package com.klmin.commerce.notificationservice;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** 본인 알림 목록 (최신순, 커서 페이징). 경로에 사용자 ID 를 받지 않아 남의 알림을 볼 방법이 없다 */
@RestController
@RequestMapping("/notifications")
class NotificationController {

    private final NotificationRepository repository;

    NotificationController(NotificationRepository repository) {
        this.repository = repository;
    }

    /** before = 앞 페이지 마지막 알림 id (UUIDv7 이라 id 순 = 시간 순). UUID 가 아니면 400 */
    @GetMapping("/me")
    List<NotificationRepository.Notification> myNotifications(
            @CurrentUserId UUID userId,
            @RequestParam(required = false) UUID before,
            @RequestParam(defaultValue = "20") @Min(1) @Max(NotificationRepository.MAX_PAGE_SIZE) int size) {
        return repository.findByUser(userId, before, size);
    }
}

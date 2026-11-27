package com.klmin.commerce.authservice.auth.adapter.out.persistence;

import com.klmin.commerce.authservice.auth.domain.Role;
import com.klmin.commerce.authservice.auth.domain.User;
import com.klmin.commerce.authservice.auth.domain.UserId;
import com.klmin.commerce.authservice.auth.domain.UserStatus;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "users")
class UserJpaEntity {

    // 가입할 때 앱이 만든 UUIDv7 (ADR 0018). DB 가 만들지 않는다
    @Id
    private UUID id;

    @Column(name = "login_id", nullable = false, length = 30)
    private String loginId;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status;

    // 역할은 몇 개 안 되고 로그인·refresh 마다 필요해 함께 읽는다
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private Set<Role> roles = new HashSet<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserJpaEntity() {
    }

    static UserJpaEntity from(User user) {
        UserJpaEntity entity = new UserJpaEntity();
        entity.id = user.id().value();
        entity.loginId = user.loginId();
        entity.passwordHash = user.passwordHash();
        entity.status = user.status();
        entity.roles = new HashSet<>(user.roles());
        entity.createdAt = user.createdAt();
        entity.updatedAt = user.updatedAt();
        return entity;
    }

    User toDomain() {
        return new User(UserId.of(id), loginId, passwordHash, EnumSet.copyOf(roles), status, createdAt, updatedAt);
    }
}

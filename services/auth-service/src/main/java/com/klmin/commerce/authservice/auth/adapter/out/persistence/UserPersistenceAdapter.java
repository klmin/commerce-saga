package com.klmin.commerce.authservice.auth.adapter.out.persistence;

import com.klmin.commerce.authservice.auth.application.DuplicateLoginIdException;
import com.klmin.commerce.authservice.auth.application.port.out.UserPort;
import com.klmin.commerce.authservice.auth.domain.User;
import com.klmin.commerce.authservice.auth.domain.UserId;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
class UserPersistenceAdapter implements UserPort {

    private final UserJpaRepository repository;

    UserPersistenceAdapter(UserJpaRepository repository) {
        this.repository = repository;
    }

    // 같은 아이디로 동시에 가입하면 먼저 찾아봐도 둘 다 없다고 본다. 유니크 제약이 막고 409 로 바꾼다
    @Override
    public User save(User user) {
        try {
            return repository.saveAndFlush(UserJpaEntity.from(user)).toDomain();
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateLoginIdException();
        }
    }

    @Override
    public Optional<User> findByLoginId(String loginId) {
        return repository.findByLoginId(loginId).map(UserJpaEntity::toDomain);
    }

    @Override
    public Optional<User> findById(UserId id) {
        return repository.findById(id.value()).map(UserJpaEntity::toDomain);
    }
}

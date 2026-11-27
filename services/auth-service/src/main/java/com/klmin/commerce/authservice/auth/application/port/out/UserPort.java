package com.klmin.commerce.authservice.auth.application.port.out;

import com.klmin.commerce.authservice.auth.domain.User;
import com.klmin.commerce.authservice.auth.domain.UserId;

import java.util.Optional;

public interface UserPort {

    /** 아이디가 이미 있으면 {@link com.klmin.commerce.authservice.auth.application.DuplicateLoginIdException} */
    User save(User user);

    Optional<User> findByLoginId(String loginId);

    Optional<User> findById(UserId id);
}

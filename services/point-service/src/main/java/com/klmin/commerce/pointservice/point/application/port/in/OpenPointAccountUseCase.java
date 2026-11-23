package com.klmin.commerce.pointservice.point.application.port.in;

import java.util.UUID;

public interface OpenPointAccountUseCase {

    /**
     * 계정이 없으면 가입 축하 포인트로 만든다. 같은 메시지의 재전달(messageId)은 건너뛰고, 내용이 같은 다른
     * 메시지·이미 있는 계정(시드 사용자 등)은 그대로 둔다. 몇 번 와도 계정은 하나, 축하 포인트도 한 번.
     */
    void open(UUID messageId, OpenPointAccountCommand command);
}

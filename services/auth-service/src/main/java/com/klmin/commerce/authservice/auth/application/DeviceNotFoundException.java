package com.klmin.commerce.authservice.auth.application;

import com.klmin.commerce.authservice.common.error.NotFoundException;

/** 이 사용자의 기기가 아니거나 이미 없다 (남의 기기도 같은 404) */
public class DeviceNotFoundException extends NotFoundException {

    public DeviceNotFoundException(String deviceId) {
        super("기기가 없습니다: " + deviceId);
    }
}

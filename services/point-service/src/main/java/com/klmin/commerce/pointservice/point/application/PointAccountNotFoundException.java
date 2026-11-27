package com.klmin.commerce.pointservice.point.application;

import com.klmin.commerce.pointservice.common.error.NotFoundException;

import java.util.UUID;

public class PointAccountNotFoundException extends NotFoundException {

    public PointAccountNotFoundException(UUID userId) {
        super("point account not found: userId=" + userId);
    }
}

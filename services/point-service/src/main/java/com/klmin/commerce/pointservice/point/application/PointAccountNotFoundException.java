package com.klmin.commerce.pointservice.point.application;

import com.klmin.commerce.pointservice.common.error.NotFoundException;

public class PointAccountNotFoundException extends NotFoundException {

    public PointAccountNotFoundException(long userId) {
        super("point account not found: userId=" + userId);
    }
}

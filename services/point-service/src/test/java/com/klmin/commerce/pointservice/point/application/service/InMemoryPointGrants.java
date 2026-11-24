package com.klmin.commerce.pointservice.point.application.service;

import com.klmin.commerce.pointservice.point.application.port.out.PointGrantPort;
import com.klmin.commerce.pointservice.point.domain.PointGrant;

import java.util.ArrayList;
import java.util.List;

/** point_grants 인메모리 대역 */
class InMemoryPointGrants implements PointGrantPort {

    final List<PointGrant> grants = new ArrayList<>();

    @Override
    public void save(PointGrant grant) {
        grants.add(grant);
    }
}

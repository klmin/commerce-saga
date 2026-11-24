package com.klmin.commerce.pointservice.point.application.service;

import com.klmin.commerce.pointservice.point.application.port.in.ListMyPointHistoryQuery;
import com.klmin.commerce.pointservice.point.application.port.in.ListMyPointHistoryUseCase;
import com.klmin.commerce.pointservice.point.application.port.in.PointHistoryEntryResult;
import com.klmin.commerce.pointservice.point.application.port.out.LoadPointHistoryPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 포인트 내역 조회. 쓰기가 아니라 애그리거트를 거치지 않고 DTO 로 바로 읽는다 */
@Service
class PointQueryService implements ListMyPointHistoryUseCase {

    private final LoadPointHistoryPort historyPort;

    PointQueryService(LoadPointHistoryPort historyPort) {
        this.historyPort = historyPort;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PointHistoryEntryResult> listMyHistory(ListMyPointHistoryQuery query) {
        return historyPort.findByUser(query.userId(), query.before(), query.size());
    }
}

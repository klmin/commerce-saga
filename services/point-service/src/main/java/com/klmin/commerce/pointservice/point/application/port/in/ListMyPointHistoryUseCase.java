package com.klmin.commerce.pointservice.point.application.port.in;

import java.util.List;

public interface ListMyPointHistoryUseCase {

    List<PointHistoryEntryResult> listMyHistory(ListMyPointHistoryQuery query);
}

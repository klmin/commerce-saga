package com.klmin.commerce.orderservice.order.application.service;

import com.klmin.commerce.orderservice.order.application.port.in.HandleSagaTimeoutUseCase;
import com.klmin.commerce.orderservice.order.application.port.out.LoadOrderPort;
import com.klmin.commerce.orderservice.order.application.port.out.OrderEventPort;
import com.klmin.commerce.orderservice.order.application.port.out.OrderSagaPort;
import com.klmin.commerce.orderservice.order.application.port.out.SagaCommandPort;
import com.klmin.commerce.orderservice.order.application.port.out.SaveOrderPort;
import com.klmin.commerce.orderservice.order.domain.OrderSaga;
import com.klmin.commerce.orderservice.order.domain.SagaAction;
import com.klmin.commerce.orderservice.order.domain.SagaState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 멈춘 Saga 정리. 판단은 {@link OrderSaga#onTimeout} 이 하고, 여기서는 잠금·저장·커맨드 발행만 한다.
 * <p>
 * 응답 처리({@code OrderService.handle})와 같은 행 락(SELECT ... FOR UPDATE)을 잡으므로, 응답과 타임아웃이 겹쳐도
 * 하나씩 처리된다. 락을 잡은 뒤 {@code updatedAt} 을 다시 보므로 그사이 응답이 와서 진행된 Saga 나
 * 다른 인스턴스가 방금 처리한 Saga 는 건드리지 않는다.
 */
@Service
class SagaTimeoutService implements HandleSagaTimeoutUseCase {

    private static final Logger log = LoggerFactory.getLogger(SagaTimeoutService.class);

    private final OrderSagaPort sagaPort;
    private final SagaActionExecutor executor;
    private final SagaTimeoutProperties properties;
    private final Clock clock;

    SagaTimeoutService(LoadOrderPort loadOrderPort, SaveOrderPort saveOrderPort, OrderSagaPort sagaPort,
                       SagaCommandPort commandPort, OrderEventPort eventPort, SagaTimeoutProperties properties,
                       Clock clock) {
        this.sagaPort = sagaPort;
        this.executor = new SagaActionExecutor(loadOrderPort, saveOrderPort, commandPort, eventPort);
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> findStuckSagas() {
        return sagaPort.findStuckOrderIds(clock.instant().minus(properties.stepTimeout()), properties.batchSize());
    }

    @Override
    @Transactional
    public boolean handleTimeout(UUID orderId) {
        Optional<OrderSaga> found = sagaPort.findByOrderIdForUpdate(orderId);
        if (found.isEmpty()) {
            return false;
        }
        OrderSaga saga = found.get();
        Instant now = clock.instant();
        SagaState timedOut = saga.state();
        SagaAction action = saga.onTimeout(now, properties.stepTimeout(), properties.maxResends());
        if (action instanceof SagaAction.Ignore) {
            return false;
        }
        sagaPort.update(saga);
        executor.execute(action, saga, now);
        if (saga.state() != timedOut) {
            log.warn("Saga 타임아웃, 보상 시작: {} -> {} (orderId={}, reason={})",
                    timedOut, saga.state(), orderId, saga.failureReason());
        } else if (saga.stepResends() > properties.maxResends()) {
            // 결제·보상 단계는 포기하지 않는다. 오래 걸리면 사람이 볼 수 있게 경고로 남긴다
            log.warn("Saga 응답 없음, 계속 재전송: {} #{} (orderId={})", timedOut, saga.stepResends(), orderId);
        } else {
            log.info("Saga 응답 없음, 커맨드 재전송: {} #{} (orderId={})", timedOut, saga.stepResends(), orderId);
        }
        return true;
    }
}

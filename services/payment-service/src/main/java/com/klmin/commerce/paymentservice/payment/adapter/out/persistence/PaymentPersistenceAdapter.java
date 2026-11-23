package com.klmin.commerce.paymentservice.payment.adapter.out.persistence;

import com.klmin.commerce.paymentservice.payment.application.port.out.PaymentPort;
import com.klmin.commerce.paymentservice.payment.domain.Payment;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
class PaymentPersistenceAdapter implements PaymentPort {

    private final PaymentJpaRepository repository;
    private final EntityManager entityManager;

    PaymentPersistenceAdapter(PaymentJpaRepository repository, EntityManager entityManager) {
        this.repository = repository;
        this.entityManager = entityManager;
    }

    @Override
    public Optional<Payment> findByOrderId(UUID orderId) {
        return repository.findByOrderId(orderId).map(PaymentPersistenceAdapter::toDomain);
    }

    @Override
    public Optional<Payment> findByOrderIdForUpdate(UUID orderId) {
        return repository.findByOrderIdForUpdate(orderId).map(PaymentPersistenceAdapter::toDomain);
    }

    @Override
    public List<UUID> findPendingOrderIds(int limit) {
        return repository.findPendingOrderIds(Limit.of(limit));
    }

    // id 를 직접 정하므로 save() 는 merge(선조회)로 동작한다. 새 결제는 항상 persist 하고 바로 flush 해서
    // order_id 유니크 위반(동시 중복 처리)이 이 트랜잭션 안에서 드러나게 한다
    @Override
    public void insert(Payment payment) {
        entityManager.persist(new PaymentJpaEntity(payment.id(), payment.orderId(), payment.userId(),
                payment.amount(), payment.status(), payment.transactionId(), payment.reason(),
                payment.cancelRequested(), payment.createdAt(), payment.updatedAt()));
        entityManager.flush();
    }

    // 같은 트랜잭션에서 잠가 조회한 영속 엔티티에 값만 옮긴다. 커밋 때 더티 체킹으로 UPDATE
    @Override
    public void update(Payment payment) {
        PaymentJpaEntity entity = repository.findById(payment.id())
                .orElseThrow(() -> new IllegalStateException("payment not found: " + payment.id()));
        entity.change(payment.status(), payment.transactionId(), payment.reason(), payment.cancelRequested(),
                payment.updatedAt());
    }

    private static Payment toDomain(PaymentJpaEntity e) {
        return Payment.restore(e.getId(), e.getOrderId(), e.getUserId(), e.getAmount(), e.getStatus(),
                e.getTransactionId(), e.getReason(), e.isCancelRequested(), e.getCreatedAt(), e.getUpdatedAt());
    }
}

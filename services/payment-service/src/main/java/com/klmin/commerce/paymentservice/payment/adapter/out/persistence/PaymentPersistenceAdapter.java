package com.klmin.commerce.paymentservice.payment.adapter.out.persistence;

import com.klmin.commerce.paymentservice.payment.application.port.out.PaymentPort;
import com.klmin.commerce.paymentservice.payment.domain.Payment;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

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

    // id 를 직접 정하므로 save() 는 merge(선조회)로 동작한다. 새 결제는 항상 persist 하고 바로 flush 해서
    // order_id 유니크 위반(동시 중복 처리)이 이 트랜잭션 안에서 드러나게 한다
    @Override
    public void insert(Payment payment) {
        entityManager.persist(new PaymentJpaEntity(payment.id(), payment.orderId(), payment.userId(),
                payment.amount(), payment.status(), payment.transactionId(), payment.reason(), payment.createdAt()));
        entityManager.flush();
    }

    private static Payment toDomain(PaymentJpaEntity e) {
        return Payment.restore(e.getId(), e.getOrderId(), e.getUserId(), e.getAmount(), e.getStatus(),
                e.getTransactionId(), e.getReason(), e.getCreatedAt());
    }
}

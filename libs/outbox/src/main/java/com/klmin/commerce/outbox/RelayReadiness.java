package com.klmin.commerce.outbox;

/** outbox 에 써도 발행이 보장되는지. 스케줄러 relay 는 행이 테이블에 남으므로 항상 된다 */
@FunctionalInterface
public interface RelayReadiness {

    RelayReadiness ALWAYS = () -> true;

    boolean ready();
}

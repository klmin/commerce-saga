package com.klmin.commerce.inventoryservice.stock.application.service;

import com.klmin.commerce.inventoryservice.stock.application.port.out.ProcessedMessagePort;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** processed_message 인메모리 대역 */
class InMemoryProcessedMessages implements ProcessedMessagePort {

    final Set<UUID> ids = new HashSet<>();

    @Override
    public boolean markProcessed(UUID messageId) {
        return ids.add(messageId);
    }
}

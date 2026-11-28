package com.klmin.commerce.searchservice.product.application.service;

import com.klmin.commerce.searchservice.product.application.port.in.IndexProductCommand;
import com.klmin.commerce.searchservice.product.application.port.in.IndexProductUseCase;
import com.klmin.commerce.searchservice.product.application.port.in.RemoveProductCommand;
import com.klmin.commerce.searchservice.product.application.port.out.ProductIndexPort;
import com.klmin.commerce.searchservice.product.domain.SearchableProduct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 상품 변경 → 검색 색인. 멱등·순서는 색인의 외부 버전(원본 LSN)이 맡는다 (ADR 0010):
 * 같은 변경의 재전달은 같은 버전, 늦게 온 옛 변경은 작은 버전이라 저장소가 거절한다. 그래서 processed_message 가 없다
 */
@Service
class ProductIndexingService implements IndexProductUseCase {

    private static final Logger log = LoggerFactory.getLogger(ProductIndexingService.class);

    private final ProductIndexPort indexPort;

    ProductIndexingService(ProductIndexPort indexPort) {
        this.indexPort = indexPort;
    }

    @Override
    public boolean index(IndexProductCommand command) {
        SearchableProduct product = new SearchableProduct(command.productId(), command.name(), command.description(),
                command.price(), command.onSale(), command.updatedAt(), command.category(), command.imageKey());
        boolean saved = indexPort.save(product, command.version());
        if (!saved) {
            log.info("같은·옛 버전이라 색인 건너뜀 (productId={}, version={})", command.productId(), command.version());
        }
        return saved;
    }

    @Override
    public boolean remove(RemoveProductCommand command) {
        return indexPort.delete(command.productId(), command.version());
    }
}

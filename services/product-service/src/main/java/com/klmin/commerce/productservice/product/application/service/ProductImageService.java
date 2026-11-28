package com.klmin.commerce.productservice.product.application.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.klmin.commerce.productservice.common.error.DomainValidationException;
import com.klmin.commerce.productservice.product.application.ProductImageNotFoundException;
import com.klmin.commerce.productservice.product.application.ProductNotFoundException;
import com.klmin.commerce.productservice.product.application.port.in.ChangeProductImageCommand;
import com.klmin.commerce.productservice.product.application.port.in.ProductImageResult;
import com.klmin.commerce.productservice.product.application.port.in.ProductImageUseCase;
import com.klmin.commerce.productservice.product.application.port.in.ProductResult;
import com.klmin.commerce.productservice.product.application.port.out.LoadProductPort;
import com.klmin.commerce.productservice.product.application.port.out.ProductImageStorePort;
import com.klmin.commerce.productservice.product.domain.ImageFormat;
import com.klmin.commerce.productservice.product.domain.ImageKey;
import com.klmin.commerce.productservice.product.domain.ProductId;
import com.klmin.commerce.productservice.product.domain.UserId;
import org.springframework.stereotype.Service;

/**
 * 상품 대표 이미지 (ADR 0021). 트랜잭션을 열지 않는다: 저장소 호출(외부 시스템)은 트랜잭션 밖에서 먼저 하고,
 * 상품 행 변경·관리 이력만 {@link ProductService#applyImage} 의 트랜잭션에서 한다.
 * 순서가 거꾸로면(DB 먼저) 상품이 없는 파일을 가리킬 수 있다. 이 순서면 실패해도 쓰이지 않는 파일만 남는다
 */
@Service
class ProductImageService implements ProductImageUseCase {

    private final ProductImageStorePort storePort;
    private final LoadProductPort loadProductPort;
    private final ProductService productService;

    ProductImageService(ProductImageStorePort storePort, LoadProductPort loadProductPort,
                        ProductService productService) {
        this.storePort = storePort;
        this.loadProductPort = loadProductPort;
        this.productService = productService;
    }

    @Override
    public ProductResult changeImage(ChangeProductImageCommand command) {
        if (command.productId() == null) {
            throw new DomainValidationException("productId must not be null");
        }
        UserId.of(command.changedBy());
        ImageFormat format = ImageFormat.detectUpload(command.data());
        // 없는 상품에 파일부터 올리지 않게 먼저 본다 (그 사이 지워질 일은 없다: 상품은 지우지 않는다)
        if (!loadProductPort.exists(command.productId())) {
            throw new ProductNotFoundException(command.productId());
        }
        ImageKey key = ImageKey.newFor(ProductId.of(command.productId()), UuidCreator.getTimeOrderedEpoch(), format);
        storePort.put(key, command.data(), format.contentType());
        return productService.applyImage(key, command.changedBy());
    }

    @Override
    public ProductImageResult image(String productId, String fileName) {
        ImageKey key;
        try {
            key = ImageKey.fromPath(productId, fileName);
        } catch (DomainValidationException e) {
            throw new ProductImageNotFoundException();
        }
        byte[] data = storePort.get(key).orElseThrow(ProductImageNotFoundException::new);
        return new ProductImageResult(data, key.format().contentType());
    }
}

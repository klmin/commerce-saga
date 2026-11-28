package com.klmin.commerce.productservice.product.application.port.out;

import com.klmin.commerce.productservice.product.domain.ImageKey;

import java.util.Optional;

/**
 * 상품 이미지 저장소 (RustFS, S3 API, ADR 0021). 트랜잭션 밖에서 부른다.
 * 저장소가 응답하지 않으면 {@code ImageStoreUnavailableException}
 */
public interface ProductImageStorePort {

    void put(ImageKey key, byte[] data, String contentType);

    /** 없으면 empty */
    Optional<byte[]> get(ImageKey key);
}

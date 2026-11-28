package com.klmin.commerce.productservice.product.application.port.in;

/** 상품 대표 이미지 올리기(관리자)·읽기(공개) (ADR 0021) */
public interface ProductImageUseCase {

    /** 저장소에 올린 뒤 상품의 이미지를 바꾸고 관리 이력을 남긴다. 저장소가 멈췄으면 503, 상품은 그대로 */
    ProductResult changeImage(ChangeProductImageCommand command);

    /** 읽기 경로 {상품 id}/images/{파일}. 모양이 틀리거나 없으면 404 */
    ProductImageResult image(String productId, String fileName);
}

package com.klmin.commerce.productservice.product.application.port.in;

/** 이미지 파일 내용과 Content-Type */
public record ProductImageResult(byte[] data, String contentType) {
}

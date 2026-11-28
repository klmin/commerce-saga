package com.klmin.commerce.productservice.product.domain;

import com.klmin.commerce.productservice.common.error.DomainValidationException;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 상품 이미지의 저장소 key: {@code products/<상품 id>/<파일 id>.<확장자>} (ADR 0021).
 * 올릴 때마다 새 파일 id(UUIDv7)라 덮어쓰지 않고, 같은 key 는 내용이 바뀌지 않아 길게 캐시해도 된다.
 * 읽기 요청의 경로도 이 모양만 받는다(다른 경로·상위 경로로 새지 않게)
 */
public record ImageKey(String value) {

    private static final Pattern FORMAT = Pattern.compile(
            "products/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/([0-9a-z-]{1,40})[.](jpg|png|webp|svg)");

    public ImageKey {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new DomainValidationException("invalid image key");
        }
    }

    public static ImageKey of(String value) {
        return new ImageKey(value);
    }

    /** 새로 올린 이미지의 key */
    public static ImageKey newFor(ProductId productId, UUID fileId, ImageFormat format) {
        return new ImageKey("products/" + productId.value() + "/" + fileId + "." + format.extension());
    }

    /** 읽기 경로({상품 id}/images/{파일}) 에서 */
    public static ImageKey fromPath(String productId, String fileName) {
        return of("products/" + productId + "/" + fileName);
    }

    public ProductId productId() {
        return ProductId.of(UUID.fromString(matcher().group(1)));
    }

    /** 상품 id 뒤의 파일 이름 (읽기 경로에 쓴다) */
    public String fileName() {
        Matcher m = matcher();
        return m.group(2) + "." + m.group(3);
    }

    public ImageFormat format() {
        return ImageFormat.fromExtension(matcher().group(3));
    }

    private Matcher matcher() {
        Matcher m = FORMAT.matcher(value);
        if (!m.matches()) {
            throw new IllegalStateException("validated in constructor");
        }
        return m;
    }
}

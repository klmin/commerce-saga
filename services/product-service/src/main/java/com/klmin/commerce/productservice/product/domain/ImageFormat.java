package com.klmin.commerce.productservice.product.domain;

import com.klmin.commerce.productservice.common.error.DomainValidationException;

/**
 * 상품 이미지 형식 (ADR 0021). 관리자가 올릴 수 있는 것은 jpg·png·webp 뿐이고, 형식은 파일 이름·Content-Type 이 아니라
 * 파일 앞부분(시그니처)으로 정한다. SVG 는 저장소에 들어 있는 시드 이미지만 (스크립트를 담을 수 있어 업로드는 받지 않는다)
 */
public enum ImageFormat {

    JPEG("jpg", "image/jpeg"),
    PNG("png", "image/png"),
    WEBP("webp", "image/webp"),
    SVG("svg", "image/svg+xml");

    /** 올릴 수 있는 크기 상한 2MB */
    public static final int MAX_UPLOAD_BYTES = 2 * 1024 * 1024;

    private final String extension;
    private final String contentType;

    ImageFormat(String extension, String contentType) {
        this.extension = extension;
        this.contentType = contentType;
    }

    public String extension() {
        return extension;
    }

    public String contentType() {
        return contentType;
    }

    /** 업로드 검사: 비었거나 2MB 를 넘거나 jpg·png·webp 시그니처가 아니면 400 */
    public static ImageFormat detectUpload(byte[] data) {
        if (data == null || data.length == 0) {
            throw new DomainValidationException("image must not be empty");
        }
        if (data.length > MAX_UPLOAD_BYTES) {
            throw new DomainValidationException("image must be at most " + MAX_UPLOAD_BYTES + " bytes");
        }
        if (startsWith(data, 0, 0xFF, 0xD8, 0xFF)) {
            return JPEG;
        }
        if (startsWith(data, 0, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return PNG;
        }
        if (startsWith(data, 0, 'R', 'I', 'F', 'F') && startsWith(data, 8, 'W', 'E', 'B', 'P')) {
            return WEBP;
        }
        throw new DomainValidationException("image must be jpg, png or webp");
    }

    /** 저장소 key 의 확장자로 (읽어 줄 때 Content-Type) */
    public static ImageFormat fromExtension(String extension) {
        for (ImageFormat format : values()) {
            if (format.extension.equals(extension)) {
                return format;
            }
        }
        throw new DomainValidationException("unknown image extension: " + extension);
    }

    private static boolean startsWith(byte[] data, int offset, int... signature) {
        if (data.length < offset + signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((data[offset + i] & 0xFF) != signature[i]) {
                return false;
            }
        }
        return true;
    }
}

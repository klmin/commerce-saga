package com.klmin.commerce.productservice.product.domain;

import com.klmin.commerce.productservice.common.error.DomainValidationException;

import java.util.regex.Pattern;

/**
 * 카테고리 코드 (ADR 0021). 생성 id 가 아니라 정해 둔 코드(digital 등)이고, 목록은 categories 테이블의 고정 시드다.
 * 있는 코드인지는 저장소를 봐야 알아서 서비스가 확인한다. 여기서는 모양만 본다(DB check 와 같은 규칙)
 */
public record CategoryCode(String value) {

    public static final int MAX_LENGTH = 30;
    // DEFAULT 보다 먼저 (정적 초기화 순서)
    private static final Pattern FORMAT = Pattern.compile("[a-z][a-z0-9-]{0," + (MAX_LENGTH - 1) + "}");
    /** 카테고리를 고르지 않은 등록(카테고리 전의 클라이언트)은 기타로 */
    public static final CategoryCode DEFAULT = new CategoryCode("etc");

    public CategoryCode {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new DomainValidationException("category code must be lowercase letters, digits or '-' (max "
                    + MAX_LENGTH + "): " + value);
        }
    }

    public static CategoryCode of(String value) {
        return new CategoryCode(value);
    }
}

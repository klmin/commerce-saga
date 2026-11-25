plugins {
    id("commerce.service-conventions")
}

// 상품 검색 (후속 4-2b, ADR 0010). products 테이블 CDC(product.cdc.products)를 받아 Elasticsearch 에 색인하고 검색 API 를 낸다.
// Elasticsearch 가 이 서비스의 저장소다(DB 없음). 클라이언트는 REST 수준만 써서(RestClient) OpenSearch 로 바꿀 여지를 둔다.
// 발행하는 메시지가 없어 outbox 도 없다
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.boot:spring-boot-starter-restclient")

    testImplementation("org.testcontainers:testcontainers-elasticsearch")
    testImplementation("org.testcontainers:testcontainers-kafka")
    testImplementation("org.awaitility:awaitility")
}

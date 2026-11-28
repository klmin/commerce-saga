# ADR 0021. 상품 카탈로그: 카테고리·대표 이미지(RustFS)·상품 상세, 화면 개편

- 상태: 채택
- 날짜: 2026-11-28

## 배경

주문·재고·포인트·결제의 정합성은 3단계와 후속 1~8 에서 끝났다. 화면은 기능 확인용에 가깝다.
상품은 이름·설명·가격·판매 여부뿐이라 목록이 글자 목록이고, 상품 상세 페이지 없이 목록에서 바로 주문 화면으로 간다.

2026-11-28 사용자 결정:

- 상품에 카테고리와 이미지를 넣고, 상품 상세 페이지를 만든다. 사용자 화면은 흔한 쇼핑몰 구성을 참고해 다시 만든다(디자인을 베끼지 않는다)
- 이미지는 주소만 저장하지 않고 실제로 올린다. 저장소는 RustFS(S3 호환, 사용자가 다른 프로젝트에서 쓰는 것)
- 화면 개편은 데이터(카테고리·이미지)가 생긴 뒤에 한다
- 카테고리는 고정 목록, 이미지는 상품당 한 장(추천안)

## 결정

### 1. 카테고리는 product-service 가 가진 고정 목록

- `categories(code varchar(30) PK, name, sort_order)` 를 Flyway 시드로 둔다(디지털·주방·생활·패션 등). 관리자가 카테고리를 추가·수정하는 기능은 없다. 바꾸려면 마이그레이션
- 키는 생성 id 가 아니라 사람이 정한 코드(`digital` 등)다. 생성되는 식별자가 아니므로 ADR 0018(UUIDv7)의 대상이 아니다. 주소(`?category=digital`)에도 그대로 쓴다
- `products.category_code` (not null, FK). 기존 상품은 마이그레이션에서 카테고리를 정한다
- 공개 API `GET /products/categories`. 상품 등록·수정 요청에 `categoryCode` 를 받고, 없는 코드면 400. 관리 이력(`product_audit_log`)에 전·후 카테고리를 남긴다(열 추가)

### 2. 대표 이미지 한 장, RustFS 에 저장하고 상품에는 key 만

- 저장소: RustFS(S3 API). 버킷 `product-images` 하나. 클라이언트는 AWS SDK v2 S3(path-style). 다른 S3 호환 저장소로 바꿔도 코드는 그대로다
- 업로드: 관리자 화면 → Gateway → product-service `POST /admin/products/{id}/image` (multipart, ADMIN).
  product-service 가 형식(파일 앞부분의 시그니처로 jpg·png·webp)과 크기(2MB 이하)를 검사하고 RustFS 에 올린 뒤, 상품의 `image_key` 를 바꾸고 관리 이력을 남긴다
  - 브라우저가 저장소에 직접 올리는 presigned URL 은 쓰지 않는다. 저장소 주소·CORS 를 밖에 드러내지 않고, 권한 검사를 기존 관리자 API 한 곳에 둔다. 이미지가 작아(2MB) 서비스를 거쳐도 부담이 없다
- 순서: 저장소에 먼저 올리고(트랜잭션 밖, `adapter.out`), 그다음 DB 트랜잭션에서 key 를 바꾼다. DB 가 실패하면 상품은 그대로고 저장소에 쓰이지 않는 파일만 남는다(무해). 반대 순서는 key 가 없는 파일을 가리킬 수 있어 쓰지 않는다
- key 는 `products/{productId}/{UUIDv7}.{확장자}`. 매번 새 key 라 덮어쓰지 않고, 브라우저·프록시가 길게 캐시해도 된다(`Cache-Control: public, max-age=31536000, immutable`). 바꾸기 전 파일은 지우지 않는다(정리 작업은 나중에, 지금은 쓰는 양이 작다)
- 이미지가 없는 상품은 화면이 카테고리별 기본 그림을 보인다

### 3. 이미지 제공: Gateway `/api/images/**` → RustFS (읽기 전용)

- 화면은 같은 출처 `/api/images/<key>` 로 읽는다. 앞단(Caddy)은 이미 `/api/*` 를 Gateway 로 보내므로 앞단 설정은 바꾸지 않는다
- Gateway 는 GET 만 열고(로그인 불필요), 버킷 경로로 바꿔 RustFS 에 보낸다. 버킷은 익명 읽기만 허용, 쓰기는 product-service 계정만
- 응답의 `imageUrl` 은 product-service 가 key 로 만든다(`/api/images/products/...`). 저장소 주소는 응답에 나가지 않는다

### 4. 검색: CDC 계약에 열을 추가하고 색인에 반영

- `product.cdc.products` 에 `category_code`·`image_key` 열을 추가한다(ADR 0010: 열은 추가만). 커넥터 `column.include.list` 를 compose·Helm 둘 다 고친다
- 색인 매핑(`dynamic: strict`)에 `category`(keyword)·`imageKey`(keyword, 색인 안 함)를 더한다. 필드 추가는 기존 색인에 바로 할 수 있어(`PUT _mapping`) 새 색인·재색인이 필요 없다. 시작할 때 없으면 더한다(멱등)
- 커넥터 설정을 바꾸기 전에 저장된 행은 새 열 없이 색인돼 있다. 커넥터를 바꾼 뒤 기존 행을 다시 보낸다(방법은 9-3 에서 정한다: Debezium 증분 스냅샷 signal 을 먼저 본다). 새 열이 없는 옛 메시지도 받아들인다(없으면 비어 있음)
- 검색 API 에 `category` 필터를 더한다. 정렬(관련도·가격·최신)은 이미 있다

### 5. 인프라

- compose: `rustfs` 와 버킷·정책·시드 이미지를 만드는 일회성 `rustfs-init`(공식 클라이언트 이미지). 시드 이미지는 저장소의 직접 만든 SVG(저작권 문제 없음)
- k8s: Helm 차트에 RustFS StatefulSet + PVC, 초기화 Job. 계정은 Secret(로컬·CI 는 개발용 값만)
- CI: compose 전체 실행(stack)·kind 검증에 포함. product-service 저장소 어댑터는 Testcontainers 로 RustFS 를 띄워 테스트한다

### 6. 화면

- 사용자 화면: 상단 헤더(로고·검색·장바구니 개수·내 주문), 카테고리 메뉴, 상품 카드 그리드(이미지·이름·가격·품절), 상품 상세 `/products/:id`(이미지·가격·재고 상태·수량·장바구니·바로 주문), 장바구니·주문서의 금액 요약, 내 주문 카드. 주문 상세의 Saga 진행 표시는 유지한다
- 관리자 화면: 등록·수정 폼에 카테고리 선택, 이미지 업로드(미리보기), 목록 썸네일
- 지금의 shadcn/ui·Tailwind 안에서 만든다(새 UI 라이브러리 없음). 다크 모드·모바일 폭을 지킨다

## 순서

| 단계 | 범위 |
|---|---|
| 9-0 | 이 ADR, PROGRESS |
| 9-1 | product: 카테고리 테이블·시드, `products.category_code`·`image_key`, 등록·수정 API 의 카테고리, `GET /products/categories`, 관리 이력 열 |
| 9-2 | RustFS(compose·Helm·CI), product 이미지 업로드 API·저장소 어댑터, Gateway `/api/images/**`, 시드 이미지 |
| 9-3 | 커넥터 열 추가, search 색인 매핑·카테고리 필터, 기존 행 다시 보내기, 시나리오 16 문서 |
| 9-4 | 관리자 화면: 카테고리·이미지 업로드·썸네일 |
| 9-5a | 사용자 화면 공통 틀: 테마·헤더·카테고리 메뉴·레이아웃 |
| 9-5b | 사용자 화면 상품: 카드 그리드·상품 상세·필터·정렬, 장바구니·주문 화면 정리, e2e |
| 9-6 | 관리자·인증 화면을 같은 톤으로 |

## 결과

- 관리자가 상품을 등록하고 카테고리·사진을 넣으면 검색(CDC)을 거쳐 사용자 화면 목록·상세에 보인다
- 인프라가 하나(RustFS) 늘고, product-service 가 외부 저장소를 쓴다. 저장소가 멈추면 이미지 업로드와 이미지 표시만 안 되고 상품·주문은 그대로다
- 교체된 이미지 파일은 저장소에 남는다(정리는 나중)
- 주문 Saga·결제·재고·인증 흐름은 바꾸지 않는다

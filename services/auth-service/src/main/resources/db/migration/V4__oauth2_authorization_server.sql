-- Spring Authorization Server JDBC 저장소 (후속 5-1, ADR 0017).
-- SAS 7.2 가 jar 에 담아 둔 스키마(oauth2-registered-client / oauth2-authorization / oauth2-authorization-consent)를
-- 그 안내대로 PostgreSQL 에 맞췄다: blob → text, timestamp → timestamptz. 열 이름·크기는 바꾸지 않는다(SAS 가 그대로 읽는다).

-- 등록된 클라이언트. 시작할 때 설정에서 upsert 한다(gateway-web, gateway-admin). id = client_id 로 넣어
-- 동시에 처음 뜬 인스턴스가 같은 행을 두 번 넣지 못하게 한다
create table oauth2_registered_client (
    id                            varchar(100)                        not null,
    client_id                     varchar(100)                        not null,
    client_id_issued_at           timestamptz default current_timestamp not null,
    client_secret                 varchar(200)  default null,
    client_secret_expires_at      timestamptz   default null,
    client_name                   varchar(200)                        not null,
    client_authentication_methods varchar(1000)                       not null,
    authorization_grant_types     varchar(1000)                       not null,
    redirect_uris                 varchar(1000) default null,
    post_logout_redirect_uris     varchar(1000) default null,
    scopes                        varchar(1000)                       not null,
    client_settings               varchar(2000)                       not null,
    token_settings                varchar(2000)                       not null,
    primary key (id),
    constraint uk_oauth2_registered_client_client_id unique (client_id)
);

-- 인가 기록: 인가 코드, access·refresh·ID 토큰. 끝난 행은 스케줄러가 지운다(auth.oauth.authorization-retention)
create table oauth2_authorization (
    id                            varchar(100) not null,
    registered_client_id          varchar(100) not null,
    principal_name                varchar(200) not null,
    authorization_grant_type      varchar(100) not null,
    authorized_scopes             varchar(1000) default null,
    attributes                    text          default null,
    state                         varchar(500)  default null,
    authorization_code_value      text          default null,
    authorization_code_issued_at  timestamptz   default null,
    authorization_code_expires_at timestamptz   default null,
    authorization_code_metadata   text          default null,
    access_token_value            text          default null,
    access_token_issued_at        timestamptz   default null,
    access_token_expires_at       timestamptz   default null,
    access_token_metadata         text          default null,
    access_token_type             varchar(100)  default null,
    access_token_scopes           varchar(1000) default null,
    oidc_id_token_value           text          default null,
    oidc_id_token_issued_at       timestamptz   default null,
    oidc_id_token_expires_at      timestamptz   default null,
    oidc_id_token_metadata        text          default null,
    refresh_token_value           text          default null,
    refresh_token_issued_at       timestamptz   default null,
    refresh_token_expires_at      timestamptz   default null,
    refresh_token_metadata        text          default null,
    user_code_value               text          default null,
    user_code_issued_at           timestamptz   default null,
    user_code_expires_at          timestamptz   default null,
    user_code_metadata            text          default null,
    device_code_value             text          default null,
    device_code_issued_at         timestamptz   default null,
    device_code_expires_at        timestamptz   default null,
    device_code_metadata          text          default null,
    primary key (id)
);

-- SAS 는 토큰 교환·갱신·검증 때 값으로 행을 찾는다. 값(JWT 등)이 길어 같음 비교만 하는 hash 인덱스로
create index ix_oauth2_authorization_state on oauth2_authorization using hash (state);
create index ix_oauth2_authorization_code on oauth2_authorization using hash (authorization_code_value);
create index ix_oauth2_authorization_access_token on oauth2_authorization using hash (access_token_value);
create index ix_oauth2_authorization_refresh_token on oauth2_authorization using hash (refresh_token_value);
create index ix_oauth2_authorization_id_token on oauth2_authorization using hash (oidc_id_token_value);
-- SSO 로그아웃 때 그 사용자의 기록을 지운다
create index ix_oauth2_authorization_principal on oauth2_authorization (principal_name);

-- 동의 기록. 우리 클라이언트는 동의 화면을 끄므로 비어 있다(SAS 가 요구하는 저장소라 둔다)
create table oauth2_authorization_consent (
    registered_client_id varchar(100)  not null,
    principal_name       varchar(200)  not null,
    authorities          varchar(1000) not null,
    primary key (registered_client_id, principal_name)
);

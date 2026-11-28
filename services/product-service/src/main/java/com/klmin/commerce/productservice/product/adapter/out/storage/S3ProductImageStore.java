package com.klmin.commerce.productservice.product.adapter.out.storage;

import com.klmin.commerce.productservice.product.application.ImageStoreUnavailableException;
import com.klmin.commerce.productservice.product.application.port.out.ProductImageStorePort;
import com.klmin.commerce.productservice.product.domain.ImageKey;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.URI;
import java.util.Optional;

/**
 * 상품 이미지 저장소 어댑터 (RustFS, ADR 0021). 버킷은 비공개다: 읽기도 이 서비스가 계정으로 가져와 돌려준다.
 * path-style 주소(http://rustfs:9000/버킷/key)라 버킷마다 DNS 가 필요 없다. 저장소 오류는 503 으로 바꾼다
 */
@Component
@EnableConfigurationProperties(ProductImageStoreProperties.class)
class S3ProductImageStore implements ProductImageStorePort, AutoCloseable {

    private final S3Client client;
    private final String bucket;

    S3ProductImageStore(ProductImageStoreProperties properties) {
        this.bucket = properties.bucket();
        this.client = S3Client.builder()
                .endpointOverride(URI.create(properties.endpoint()))
                .region(Region.of(properties.region()))
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())))
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(properties.connectTimeout())
                        .socketTimeout(properties.readTimeout()))
                .build();
    }

    @Override
    public void put(ImageKey key, byte[] data, String contentType) {
        try {
            putObject(key, data, contentType);
        } catch (NoSuchBucketException e) {
            // 시드가 아직 버킷을 못 만들었으면(저장소가 늦게 떴다) 여기서 만들고 한 번 더
            try {
                ensureBucket();
                putObject(key, data, contentType);
            } catch (SdkException retry) {
                throw new ImageStoreUnavailableException(retry);
            }
        } catch (SdkException e) {
            throw new ImageStoreUnavailableException(e);
        }
    }

    private void putObject(ImageKey key, byte[] data, String contentType) {
        client.putObject(b -> b.bucket(bucket).key(key.value()).contentType(contentType), RequestBody.fromBytes(data));
    }

    @Override
    public Optional<byte[]> get(ImageKey key) {
        try {
            return Optional.of(client.getObjectAsBytes(b -> b.bucket(bucket).key(key.value())).asByteArray());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw new ImageStoreUnavailableException(e);
        } catch (SdkException e) {
            throw new ImageStoreUnavailableException(e);
        }
    }

    /** 있으면 true. 시드가 이미 올라갔는지 볼 때 */
    boolean exists(ImageKey key) {
        try {
            client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key.value()).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    /** 버킷이 없으면 만든다 (여러 인스턴스가 동시에 불러도 결과가 같다) */
    void ensureBucket() {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (NoSuchBucketException e) {
            createBucket();
        } catch (S3Exception e) {
            if (e.statusCode() != 404) {
                throw e;
            }
            createBucket();
        }
    }

    private void createBucket() {
        try {
            client.createBucket(b -> b.bucket(bucket));
        } catch (S3Exception e) {
            // 다른 인스턴스가 먼저 만들었다
            if (e.statusCode() != 409) {
                throw e;
            }
        }
    }

    @Override
    public void close() {
        client.close();
    }
}

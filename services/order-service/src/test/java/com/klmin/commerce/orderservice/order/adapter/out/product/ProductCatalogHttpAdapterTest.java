package com.klmin.commerce.orderservice.order.adapter.out.product;

import com.sun.net.httpserver.HttpServer;
import com.klmin.commerce.orderservice.order.application.ProductCatalogUnavailableException;
import com.klmin.commerce.orderservice.order.application.port.out.ProductCatalogPort.CatalogProduct;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** product-service 를 JDK HttpServer 로 흉내 내 응답·오류·타임아웃을 본다 */
class ProductCatalogHttpAdapterTest {

    private final AtomicReference<String> query = new AtomicReference<>();
    private HttpServer server;

    private ProductCatalogHttpAdapter adapter(int status, String body, long delayMillis) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/products", exchange -> {
            query.set(exchange.getRequestURI().getQuery());
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return new ProductCatalogHttpAdapter(RestClient.builder(), new ProductCatalogProperties(
                "http://localhost:" + server.getAddress().getPort(), Duration.ofMillis(300)));
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void readsCurrentPrices() throws Exception {
        var adapter = adapter(200, """
                {"items":[{"id":1,"name":"키보드","description":"","price":39000,"onSale":true},
                          {"id":2,"name":"마우스","description":"","price":25000,"onSale":false}]}
                """, 0);

        List<CatalogProduct> products = adapter.findByIds(List.of(1L, 2L, 3L));

        assertThat(query.get()).isEqualTo("ids=1,2,3");
        assertThat(products).containsExactly(new CatalogProduct(1, 39_000, true), new CatalogProduct(2, 25_000, false));
    }

    @Test
    void serverError_isUnavailable() throws Exception {
        var adapter = adapter(500, "{}", 0);

        assertThatThrownBy(() -> adapter.findByIds(List.of(1L)))
                .isInstanceOf(ProductCatalogUnavailableException.class);
    }

    @Test
    void slowResponse_timesOut() throws Exception {
        var adapter = adapter(200, "{\"items\":[]}", 2_000);

        long start = System.nanoTime();
        assertThatThrownBy(() -> adapter.findByIds(List.of(1L)))
                .isInstanceOf(ProductCatalogUnavailableException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1_500));
    }

    @Test
    void connectionRefused_isUnavailable() {
        var adapter = new ProductCatalogHttpAdapter(RestClient.builder(),
                new ProductCatalogProperties("http://localhost:1", Duration.ofMillis(300)));

        assertThatThrownBy(() -> adapter.findByIds(List.of(1L)))
                .isInstanceOf(ProductCatalogUnavailableException.class);
    }
}

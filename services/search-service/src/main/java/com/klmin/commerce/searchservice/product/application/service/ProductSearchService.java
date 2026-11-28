package com.klmin.commerce.searchservice.product.application.service;

import com.klmin.commerce.searchservice.product.application.port.in.ProductSearchResult;
import com.klmin.commerce.searchservice.product.application.port.in.SearchProductsQuery;
import com.klmin.commerce.searchservice.product.application.port.in.SearchProductsUseCase;
import com.klmin.commerce.searchservice.product.application.port.out.ProductSearchPort;
import com.klmin.commerce.searchservice.product.domain.SearchCriteria;
import com.klmin.commerce.searchservice.product.domain.SearchPage;
import com.klmin.commerce.searchservice.product.domain.SearchSort;
import org.springframework.stereotype.Service;

@Service
class ProductSearchService implements SearchProductsUseCase {

    private final ProductSearchPort searchPort;

    ProductSearchService(ProductSearchPort searchPort) {
        this.searchPort = searchPort;
    }

    @Override
    public ProductSearchResult search(SearchProductsQuery query) {
        SearchCriteria criteria = new SearchCriteria(query.keyword(), query.minPrice(), query.maxPrice(),
                query.onSaleOnly(), SearchSort.parse(query.sort()), query.page(), query.size(), query.category());
        SearchPage page = searchPort.search(criteria);
        return new ProductSearchResult(
                page.items().stream()
                        .map(p -> new ProductSearchResult.Item(p.id(), p.name(), p.description(), p.price(), p.onSale(),
                                p.category(), p.imagePath()))
                        .toList(),
                page.total(), criteria.page(), criteria.size());
    }
}

package com.example.app.service;

import com.example.app.domain.MovementType;
import com.example.app.repository.ProductRepository;
import com.example.app.repository.StockRepository;

import java.time.Instant;

/**
 * The only place stock changes. Every change writes a movement row and updates the cached product quantity.
 * Callers must run it inside a transaction so the two writes stay together.
 */
final class StockLedger {

    private final ProductRepository products;
    private final StockRepository movements;

    StockLedger(ProductRepository products, StockRepository movements) {
        this.products = products;
        this.movements = movements;
    }

    void apply(long productId, MovementType type, int qtyDelta, String reason, long userId, Long refSaleId) {
        movements.insertMovement(productId, type, qtyDelta, reason, userId, refSaleId, Instant.now());
        products.adjustStock(productId, qtyDelta);
    }
}

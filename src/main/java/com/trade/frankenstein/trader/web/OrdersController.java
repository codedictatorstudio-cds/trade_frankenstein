package com.trade.frankenstein.trader.web;

import com.trade.frankenstein.trader.common.exception.Http;
import com.trade.frankenstein.trader.service.OrdersService;
import com.upstox.api.PlaceOrderRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrdersController {

    private final OrdersService orders;

    @PostMapping
    public ResponseEntity<?> place(@RequestBody PlaceOrderRequest req) {
        return Http.from(orders.placeOrder(req));
    }

    @DeleteMapping("/{orderId}")
    public ResponseEntity<?> cancel(@PathVariable("orderId") String orderId) {
        return Http.from(orders.cancelOrder(orderId));
    }

    @GetMapping
    public ResponseEntity<?> list() {
        return Http.from(orders.listOrders(null, null));
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<?> get(@PathVariable("orderId") String orderId) {
        return Http.from(orders.getOrder(orderId));
    }
}

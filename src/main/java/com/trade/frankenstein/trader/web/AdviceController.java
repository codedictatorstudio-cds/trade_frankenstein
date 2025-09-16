package com.trade.frankenstein.trader.web;

import com.trade.frankenstein.trader.common.exception.Http;
import com.trade.frankenstein.trader.model.documents.Advice;
import com.trade.frankenstein.trader.service.AdviceService; // align with actual package
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/advice")
@RequiredArgsConstructor
public class AdviceController {

    private final AdviceService adviceService;

    @GetMapping
    public ResponseEntity<?> list() {
        return Http.from(adviceService.list());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable("id") String id) {
        return Http.from(adviceService.get(id));
    }

    // Optional create endpoint for Engine/Strategy to persist + broadcast new advice
    @PostMapping
    public ResponseEntity<?> create(@RequestBody Advice draft) {
        return Http.from(adviceService.create(draft)); // emits advice.new
    }

    @PostMapping("/{id}/execute")
    public ResponseEntity<?> execute(@PathVariable("id") String id) {
        return Http.from(adviceService.execute(id));
    }

    @PostMapping("/{id}/dismiss")
    public ResponseEntity<?> dismiss(@PathVariable("id") String id) {
        return Http.from(adviceService.dismiss(id));
    }
}

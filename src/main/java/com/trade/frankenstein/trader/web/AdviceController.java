package com.trade.frankenstein.trader.web;

import com.trade.frankenstein.trader.common.exception.Http;
import com.trade.frankenstein.trader.model.documents.Advice;
import com.trade.frankenstein.trader.service.AdviceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/advice")
@RequiredArgsConstructor
public class AdviceController {

    private final AdviceService adviceService;

    // === NEW: needed by ExecutionAdvicesCard ===
    @GetMapping("/list")
    public ResponseEntity<?> list() {
        // returns JSON array (List<Advice>) via Http.from(Result)
        return Http.from(adviceService.list());
    }

    // === NEW: needed by ExecutionAdvicesCard ===
    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable("id") String id) {
        return Http.from(adviceService.get(id));
    }

    // (Optional) If you actually create advices from UI/other clients
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
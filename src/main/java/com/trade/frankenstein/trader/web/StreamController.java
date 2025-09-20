package com.trade.frankenstein.trader.web;

import com.trade.frankenstein.trader.service.StreamGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class StreamController {

    private static final List<String> DEFAULT_TOPICS = Arrays.asList(
            "heartbeat",          // gateway heartbeat
            "engine.state",
            "engine.heartbeat",   // sent by EngineService.tick()
            "decision.quality",
            "risk.summary",
            "risk.circuit",
            "order.*",            // placed/modified/cancelled
            "orders.*",           // advice path uses orders.created
            "trade.*",
            "sentiment.update"
    );

    private final StreamGateway stream;

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(name = "timeoutMs", required = false) Long timeoutMs,
                             @RequestParam(name = "topics", required = false) String topics) {

        if (!StringUtils.hasText(topics) || "*".equals(topics.trim())) {
            return stream.subscribe(timeoutMs, DEFAULT_TOPICS);
        }
        return stream.subscribeCsv(timeoutMs, topics);
    }
}

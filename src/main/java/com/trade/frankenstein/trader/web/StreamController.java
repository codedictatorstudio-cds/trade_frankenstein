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
            "engine.heartbeat",
            "decision.quality",
            "risk.summary",
            "risk.circuit",
            "order.*",
            "trade.*",
            "sentiment.update",
            "advice.new",
            "advice.updated",
            "trade.created",
            "trade.updates"
    );

    private final StreamGateway stream;

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(name = "id", required = false) String id,
                             @RequestParam(name = "topics", required = false) String topicsCsv) {

        // If topics is blank or "*", subscribe to safe defaults
        if (!StringUtils.hasText(topicsCsv) || "*".equals(topicsCsv.trim())) {
            return stream.subscribe(id, DEFAULT_TOPICS);  // subscribe(List<String>)
        }

        // CSV: "advice.new,trade.created"
        return stream.subscribeCsv(id, topicsCsv);        // subscribeCsv(String)
    }
}

package com.trade.frankenstein.trader.web;

import com.trade.frankenstein.trader.service.streaming.StreamGateway;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api")
public class StreamController {

    private final StreamGateway stream;

    public StreamController(StreamGateway stream) {
        this.stream = stream;
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(name = "timeoutMs", required = false) Long timeoutMs,
                             @RequestParam(name = "topics", required = false) String topics) {
        return stream.subscribeCsv(timeoutMs, topics);
    }
}

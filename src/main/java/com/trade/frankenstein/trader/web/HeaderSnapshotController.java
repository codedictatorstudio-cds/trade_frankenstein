package com.trade.frankenstein.trader.web;

import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.service.decision.DecisionService;
import com.trade.frankenstein.trader.service.EngineService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class HeaderSnapshotController {

    private final EngineService engineService;
    private final DecisionService decisionService;

    @GetMapping("/api/header/snapshot")
    public Map<String, Object> snapshot() {
        Map<String, Object> root = new HashMap<>();

        // ---- Engine status ----------------------------------------------------
        String status = "UNKNOWN";
        try {
            Result<EngineService.EngineState> r = engineService.getEngineState();
            if (r != null && r.isOk() && r.get() != null) {
                EngineService.EngineState s = r.get();
                if (s.getLastError() != null && !s.getLastError().trim().isEmpty()) {
                    status = "ERROR";
                } else if (s.isRunning()) {
                    status = "RUNNING";
                } else {
                    status = "STOPPED";
                }
            }
        } catch (Throwable ignored) {
        }
        Map<String, Object> engine = new HashMap<>();
        engine.put("status", status);
        root.put("engine", engine);

        // ---- Model id (system property or env) --------------------------------
        String modelId = propOrEnv("model.id", "MODEL_ID", "-");
        root.put("modelId", modelId);

        // ---- Inference p95 (not yet computed server-side; -1 means “unknown”) --
        Map<String, Object> infer = new HashMap<>();
        long p95 = -1L;
        try {
            p95 = decisionService.getInferenceP95Millis();
        } catch (Throwable ignored) {
        }
        infer.put("p95", p95);
        root.put("infer", infer);

        // ---- Build meta from system properties / env --------------------------
        String ver = propOrEnv("build.version", "BUILD_VERSION", "-");
        String time = propOrEnv("build.time", "BUILD_TIME", Instant.now().toString());
        Map<String, Object> build = new HashMap<>();
        build.put("version", ver);
        build.put("time", time);
        root.put("build", build);

        return root;
    }

    private static String propOrEnv(String prop, String env, String def) {
        String v = System.getProperty(prop);
        if (v == null || v.trim().isEmpty()) {
            v = System.getenv(env);
        }
        return (v == null || v.trim().isEmpty()) ? def : v;
    }
}

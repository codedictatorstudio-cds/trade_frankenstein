package com.trade.frankenstein.trader.web;

import com.trade.frankenstein.trader.common.enums.BotFeature;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.togglz.core.manager.FeatureManager;
import org.togglz.core.repository.FeatureState;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


@RestController
@RequestMapping("/api/admin/flags")
public class AdminFlagsController {

    private final FeatureManager featureManager;

    public AdminFlagsController(FeatureManager featureManager) {
        this.featureManager = featureManager;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return Arrays.stream(BotFeature.values()).map(f -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", f.name());
            m.put("label", f.name());
            m.put("enabled", featureManager.isActive(f));
            return m;
        }).collect(Collectors.toList());
    }

    @PutMapping("/{name}")
    public ResponseEntity<?> set(@PathVariable("name") String name, @RequestParam("enabled") boolean enabled) {
        BotFeature f;
        try {
            f = BotFeature.valueOf(name);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body("Unknown feature: " + name);
        }
        FeatureState state = new FeatureState(f, enabled);
        featureManager.setFeatureState(state);
        return ResponseEntity.ok().build();
    }
}

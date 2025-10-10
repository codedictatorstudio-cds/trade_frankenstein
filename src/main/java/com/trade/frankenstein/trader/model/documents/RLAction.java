package com.trade.frankenstein.trader.model.documents;

import com.trade.frankenstein.trader.enums.ActionType;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

@Document
@Data
public class RLAction {

    @Id
    private String id;
    private ActionType actionType;
    private BigDecimal confidence;
    private Map<String, Double> parameters;
    private BigDecimal expectedReward;
    private String agentType; // PPO, SAC, DQN
    private Map<String, Object> state;
    private Instant createdAt;

    public String toReason() {
        return String.format("RL Action: %s (conf: %.2f, reward: %.3f)",
                actionType, confidence.doubleValue(), expectedReward.doubleValue());
    }
}
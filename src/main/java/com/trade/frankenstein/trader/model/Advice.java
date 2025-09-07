package com.trade.frankenstein.trader.model;

import com.trade.frankenstein.trader.enums.AdviceStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Advice {
    
    private  String id;        // for backend actions
    private  String time;      // "HH:mm:ss"
    private  String instrument;
    private  String side;      // BUY | SELL
    private  int confidence;   // 0..100
    private  int tech;         // 0..100
    private  int news;         // 0..100
    private  String status;    // "Pending" | "Executed" | "Blocked"
    private  String reason;    // may be "—"
    private  String orderId;   // may be "—"
    private Boolean canExecute;     // null or false -> disable Execute
    private String  blockedReason;
    private AdviceStatus adviceStatus; // INTERNAL USE ONLY (not serialized)


}

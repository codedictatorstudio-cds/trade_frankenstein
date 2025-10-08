package com.trade.frankenstein.trader.web;

import com.trade.frankenstein.trader.common.exception.Http;
import com.trade.frankenstein.trader.enums.OptionType;
import com.trade.frankenstein.trader.service.options.OptionChainService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/options")
@RequiredArgsConstructor
public class OptionChainController {

    private final OptionChainService optionChain;

    @GetMapping("/expiries")
    public ResponseEntity<?> listNearestExpiries(@RequestParam("underlying") String underlying,
                                                 @RequestParam(name = "count", defaultValue = "5") int count) {
        int c = Math.max(1, count);
        return Http.from(optionChain.listNearestExpiries(underlying, c));
    }

    @GetMapping("/contracts")
    public ResponseEntity<?> listContractsByStrikeRange(@RequestParam("underlying") String underlying,
                                                        @RequestParam("expiry") LocalDate expiry,
                                                        @RequestParam("minStrike") BigDecimal minStrike,
                                                        @RequestParam("maxStrike") BigDecimal maxStrike) {
        return Http.from(optionChain.listContractsByStrikeRange(underlying, expiry, minStrike, maxStrike));
    }

    @GetMapping("/contract")
    public ResponseEntity<?> findContract(@RequestParam("underlying") String underlying,
                                          @RequestParam("expiry") LocalDate expiry,
                                          @RequestParam("strike") BigDecimal strike,
                                          @RequestParam("type") OptionType type) {
        return Http.from(optionChain.findContract(underlying, expiry, strike, type));
    }

    @GetMapping("/pcr/oi")
    public ResponseEntity<?> getOiPcr(@RequestParam("underlying") String underlying,
                                      @RequestParam("expiry") LocalDate expiry) {
        return Http.from(optionChain.getOiPcr(underlying, expiry));
    }

    @GetMapping("/pcr/volume")
    public ResponseEntity<?> getVolumePcr(@RequestParam("underlying") String underlying,
                                          @RequestParam("expiry") LocalDate expiry) {
        return Http.from(optionChain.getVolumePcr(underlying, expiry));
    }

    @GetMapping("/atm")
    public ResponseEntity<?> computeAtm(@RequestParam("indexPrice") BigDecimal indexPrice,
                                        @RequestParam("step") int step) {
        return ResponseEntity.ok(optionChain.computeAtmStrike(indexPrice, step));
    }
}

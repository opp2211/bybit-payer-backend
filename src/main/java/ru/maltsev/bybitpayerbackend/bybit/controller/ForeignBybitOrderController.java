package ru.maltsev.bybitpayerbackend.bybit.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ru.maltsev.bybitpayerbackend.bybit.dto.ForeignBybitOrderResponse;
import ru.maltsev.bybitpayerbackend.bybit.service.ForeignBybitOrderService;

@RestController
@RequestMapping("/api/foreign-orders")
public class ForeignBybitOrderController {

    private final ForeignBybitOrderService foreignBybitOrderService;

    public ForeignBybitOrderController(ForeignBybitOrderService foreignBybitOrderService) {
        this.foreignBybitOrderService = foreignBybitOrderService;
    }

    @GetMapping("/active")
    public List<ForeignBybitOrderResponse> getActive() {
        return foreignBybitOrderService.getActive();
    }

    @GetMapping("/{id}")
    public ForeignBybitOrderResponse getDetails(@PathVariable Long id) {
        return foreignBybitOrderService.getDetails(id);
    }

    @PostMapping("/{id}/cancel")
    public ForeignBybitOrderResponse requestCancel(@PathVariable Long id) {
        return foreignBybitOrderService.requestCancel(id);
    }
}

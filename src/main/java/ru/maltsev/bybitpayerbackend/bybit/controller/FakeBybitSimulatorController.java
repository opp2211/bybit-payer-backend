package ru.maltsev.bybitpayerbackend.bybit.controller;

import java.util.List;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import ru.maltsev.bybitpayerbackend.bybit.dto.FakeBybitAdResponse;
import ru.maltsev.bybitpayerbackend.bybit.dto.FakeBybitOrderCreateRequest;
import ru.maltsev.bybitpayerbackend.bybit.dto.FakeBybitOrderResponse;
import ru.maltsev.bybitpayerbackend.bybit.dto.SendChatMessageRequest;
import ru.maltsev.bybitpayerbackend.bybit.service.FakeBybitSimulatorService;

@RestController
@Profile("local")
@RequestMapping("/api/local/bybit-simulator")
@RequiredArgsConstructor
public class FakeBybitSimulatorController {

    private final FakeBybitSimulatorService simulatorService;

    @GetMapping("/ads")
    public List<FakeBybitAdResponse> getPublishedAds() {
        return simulatorService.getPublishedAds();
    }

    @GetMapping("/orders/active")
    public List<FakeBybitOrderResponse> getActiveOrders() {
        return simulatorService.getActiveOrders();
    }

    @PostMapping("/ads/{bybitAdId}/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public FakeBybitOrderResponse createOrder(
            @PathVariable String bybitAdId,
            @Valid @RequestBody FakeBybitOrderCreateRequest request
    ) {
        return simulatorService.createOrder(bybitAdId, request.amountRub());
    }

    @GetMapping("/orders/{bybitOrderId}")
    public FakeBybitOrderResponse getOrder(@PathVariable String bybitOrderId) {
        return simulatorService.getOrder(bybitOrderId);
    }

    @PostMapping("/orders/{bybitOrderId}/messages")
    public FakeBybitOrderResponse sendMessage(
            @PathVariable String bybitOrderId,
            @Valid @RequestBody SendChatMessageRequest request
    ) {
        return simulatorService.sendMessage(bybitOrderId, request.message());
    }

    @PostMapping("/orders/{bybitOrderId}/mark-paid")
    public FakeBybitOrderResponse markPaid(@PathVariable String bybitOrderId) {
        return simulatorService.markPaid(bybitOrderId);
    }

    @PostMapping("/orders/{bybitOrderId}/cancel")
    public FakeBybitOrderResponse cancel(@PathVariable String bybitOrderId) {
        return simulatorService.cancel(bybitOrderId);
    }

    @DeleteMapping("/state")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset() {
        simulatorService.reset();
    }
}

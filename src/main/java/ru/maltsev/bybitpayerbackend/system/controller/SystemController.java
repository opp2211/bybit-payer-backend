package ru.maltsev.bybitpayerbackend.system.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ru.maltsev.bybitpayerbackend.system.dto.SystemStatusResponse;
import ru.maltsev.bybitpayerbackend.system.service.SystemStatusService;

@RestController
@RequestMapping("/api/workspaces/{workspacePublicId}/system")
@RequiredArgsConstructor
public class SystemController {

    private final SystemStatusService systemStatusService;

    @GetMapping("/status")
    public SystemStatusResponse getStatus(@PathVariable String workspacePublicId) {
        return systemStatusService.getStatus(workspacePublicId);
    }

    public SystemStatusResponse getStatus() {
        return systemStatusService.getStatus();
    }

    @PostMapping("/resync")
    public SystemStatusResponse resync(@PathVariable String workspacePublicId) {
        return systemStatusService.resync(workspacePublicId);
    }
}

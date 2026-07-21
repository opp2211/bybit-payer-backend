package ru.maltsev.bybitpayerbackend.workspace.controller;

import java.util.List;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import ru.maltsev.bybitpayerbackend.workspace.dto.AddWorkspaceMemberRequest;
import ru.maltsev.bybitpayerbackend.workspace.dto.CreateWorkspaceRequest;
import ru.maltsev.bybitpayerbackend.workspace.dto.WorkspaceMemberResponse;
import ru.maltsev.bybitpayerbackend.workspace.dto.WorkspaceResponse;
import ru.maltsev.bybitpayerbackend.workspace.service.WorkspaceService;

@RestController
@RequestMapping("/api/workspaces")
@RequiredArgsConstructor
public class WorkspaceController {

    private final WorkspaceService workspaceService;

    @GetMapping
    public List<WorkspaceResponse> list() {
        return workspaceService.listCurrentUserWorkspaces();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WorkspaceResponse create(@Valid @RequestBody CreateWorkspaceRequest request) {
        return workspaceService.create(request);
    }

    @GetMapping("/{workspacePublicId}")
    public WorkspaceResponse getDetails(@PathVariable String workspacePublicId) {
        return workspaceService.getDetails(workspacePublicId);
    }

    @DeleteMapping("/{workspacePublicId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String workspacePublicId) {
        workspaceService.softDelete(workspacePublicId);
    }

    @GetMapping("/{workspacePublicId}/members")
    public List<WorkspaceMemberResponse> listMembers(@PathVariable String workspacePublicId) {
        return workspaceService.listMembers(workspacePublicId);
    }

    @PostMapping("/{workspacePublicId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public WorkspaceMemberResponse addMember(
            @PathVariable String workspacePublicId,
            @Valid @RequestBody AddWorkspaceMemberRequest request
    ) {
        return workspaceService.addMember(workspacePublicId, request);
    }

    @DeleteMapping("/{workspacePublicId}/members/{userPublicId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(
            @PathVariable String workspacePublicId,
            @PathVariable String userPublicId
    ) {
        workspaceService.removeMember(workspacePublicId, userPublicId);
    }
}

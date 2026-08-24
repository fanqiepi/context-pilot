package io.github.fanqiepi.contextpilot.memory;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/user-memories")
@Tag(name = "回答偏好管理", description = "管理当前隐式单用户的显式回答偏好")
public class UserMemoryController {

    private final UserMemoryService userMemoryService;

    public UserMemoryController(UserMemoryService userMemoryService) {
        this.userMemoryService = userMemoryService;
    }

    @GetMapping
    @Operation(summary = "查询回答偏好")
    public List<UserMemoryResponse> list(
            @RequestParam(required = false) UserMemoryStatus status,
            @RequestParam(required = false) Boolean expired) {
        return userMemoryService.list(status, expired);
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询回答偏好详情与版本摘要")
    public UserMemoryDetailResponse get(@PathVariable UUID id) {
        return userMemoryService.get(id);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "纠正、启停或调整回答偏好过期时间")
    public UserMemoryDetailResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody UserMemoryUpdateRequest request) {
        return userMemoryService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "逻辑删除回答偏好")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        userMemoryService.delete(id);
        return ResponseEntity.noContent().build();
    }
}

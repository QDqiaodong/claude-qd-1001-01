package com.paper.mill.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** 发起纸机检修请求：记录原因、开始时间、预计结束时间和经办人。 */
@Getter
@Setter
public class MaintenanceStartRequest {

    @NotBlank(message = "检修原因不能为空")
    @Size(max = 256, message = "检修原因长度不能超过 256")
    private String reason;

    @NotNull(message = "检修开始时间不能为空")
    private LocalDateTime startedAt;

    @NotNull(message = "预计结束时间不能为空")
    private LocalDateTime expectedEndAt;

    @NotBlank(message = "经办人不能为空")
    @Size(max = 32, message = "经办人长度不能超过 32")
    private String operator;
}

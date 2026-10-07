package com.paper.mill.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** 纸机状态动作请求：运行/停机/检修开始/检修完成/恢复运行都必须留下经办人。 */
@Getter
@Setter
public class MachineActionRequest {

    /** 停机 / 运行 / 开始检修 / 完成检修 / 恢复运行 */
    @NotBlank(message = "状态动作不能为空")
    private String action;

    @NotBlank(message = "经办人不能为空")
    @Size(max = 32, message = "经办人长度不能超过 32")
    private String operator;

    /** 开始检修原因 */
    @Size(max = 256, message = "检修原因长度不能超过 256")
    private String reason;

    /** 预计检修结束时间 */
    private LocalDateTime expectedFinishAt;
}

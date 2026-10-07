package com.paper.mill.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** 检修完成 / 恢复运行的经办人确认请求。 */
@Getter
@Setter
public class MaintenanceOperatorRequest {

    @NotBlank(message = "经办人不能为空")
    @Size(max = 32, message = "经办人长度不能超过 32")
    private String operator;
}

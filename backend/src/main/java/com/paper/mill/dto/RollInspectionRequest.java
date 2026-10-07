package com.paper.mill.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** 母卷检验动作；标记「可用」时必须带经办人并受纸机检修准入约束。 */
@Getter
@Setter
public class RollInspectionRequest {

    @NotBlank(message = "目标状态不能为空")
    private String target;

    private String grade;

    @NotBlank(message = "经办人不能为空")
    @Size(max = 32, message = "经办人长度不能超过 32")
    private String operator;
}

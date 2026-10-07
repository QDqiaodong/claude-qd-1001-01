package com.paper.mill.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** 待检母卷判可用请求；检修期间被阻止时按 requestToken 留痕。 */
@Getter
@Setter
public class MarkUsableRequest {

    @NotBlank(message = "缺少请求令牌（requestToken），无法防止重复提交")
    @Size(max = 64, message = "请求令牌长度不能超过 64")
    private String requestToken;

    @NotBlank(message = "经办人不能为空")
    @Size(max = 32, message = "经办人长度不能超过 32")
    private String operator;

    /** 可选：检验后人工调整等级 */
    @Size(max = 16, message = "等级长度不能超过 16")
    private String grade;
}

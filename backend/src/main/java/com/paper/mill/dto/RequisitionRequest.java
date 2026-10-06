package com.paper.mill.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 切裁领料登记请求体。
 *
 * <p>注解只做「数字本身」的兜底校验；订单状态、母卷状态、规格匹配、不超过母卷余量等
 * 业务条件全部由 {@code RequisitionService} 在一个事务内重新校验——前端禁用按钮不可信。</p>
 *
 * @param requestToken 幂等令牌：前端每次「点击」生成一个，重复点击/网络重试沿用同一个，
 *                     同令牌重复到达只返回原结果；换一次新点击必须换新令牌
 */
@Getter
@Setter
public class RequisitionRequest {

    /** 幂等令牌，一次点击一个 */
    @NotBlank(message = "缺少请求令牌（requestToken），无法防止重复提交")
    @Size(max = 64, message = "请求令牌长度不能超过 64")
    private String requestToken;

    /** 母卷号 */
    @NotBlank(message = "母卷号不能为空")
    @Size(max = 32, message = "母卷号长度不能超过 32")
    private String rollNo;

    /** 本次领料长度（米），必须为正整数 */
    @NotNull(message = "本次领料长度不能为空")
    @Positive(message = "本次领料长度必须大于 0")
    private Integer lengthM;

    /** 本次领料重量（千克），必须为正数且最多两位小数 */
    @NotNull(message = "本次领料重量不能为空")
    @DecimalMin(value = "0.01", message = "本次领料重量必须大于 0")
    @Digits(integer = 8, fraction = 2, message = "本次领料重量最多两位小数")
    private BigDecimal weightKg;

    /** 操作人（车间登记的当班操作员姓名/工号） */
    @NotBlank(message = "操作人不能为空")
    @Size(max = 32, message = "操作人长度不能超过 32")
    private String operator;
}

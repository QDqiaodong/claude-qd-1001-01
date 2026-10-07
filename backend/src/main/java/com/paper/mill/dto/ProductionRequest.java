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

/** 母卷产出登记请求；requestToken 用于检修准入失败后的持久留痕与重复提交重放。 */
@Getter
@Setter
public class ProductionRequest {

    @NotBlank(message = "缺少请求令牌（requestToken），无法防止重复提交")
    @Size(max = 64, message = "请求令牌长度不能超过 64")
    private String requestToken;

    @NotBlank(message = "纸卷号不能为空")
    @Size(max = 32, message = "纸卷号长度不能超过 32")
    private String rollNo;

    @NotNull(message = "必须指定所属纸机")
    private Long machineId;

    @NotNull(message = "定量必须大于 0")
    @Positive(message = "定量必须大于 0")
    private Integer grammageGsm;

    @NotNull(message = "幅宽必须大于 0")
    @Positive(message = "幅宽必须大于 0")
    private Integer widthMm;

    @NotNull(message = "长度必须大于 0")
    @Positive(message = "长度必须大于 0")
    private Integer lengthM;

    @NotNull(message = "重量必须大于 0")
    @DecimalMin(value = "0.01", message = "重量必须大于 0")
    @Digits(integer = 8, fraction = 2, message = "重量最多两位小数")
    private BigDecimal weightKg;

    /** 本批目标定量，用于自动判等；为空则以实测定量为准 */
    @Positive(message = "目标定量必须大于 0")
    private Integer targetGsm;

    @NotBlank(message = "经办人不能为空")
    @Size(max = 32, message = "经办人长度不能超过 32")
    private String operator;

    public Integer getTargetGsm() {
        return targetGsm;
    }
}

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

/** 抄纸母卷产出登记请求。 */
@Getter
@Setter
public class PaperRollProduceRequest {

    @NotBlank(message = "纸卷号不能为空")
    @Size(max = 32, message = "纸卷号长度不能超过 32")
    private String rollNo;

    @NotNull(message = "定量不能为空")
    @Positive(message = "定量必须大于 0")
    private Integer grammageGsm;

    @NotNull(message = "幅宽不能为空")
    @Positive(message = "幅宽必须大于 0")
    private Integer widthMm;

    @NotNull(message = "长度不能为空")
    @Positive(message = "长度必须大于 0")
    private Integer lengthM;

    @NotNull(message = "重量不能为空")
    @DecimalMin(value = "0.01", message = "重量必须大于 0")
    @Digits(integer = 8, fraction = 2, message = "重量最多两位小数")
    private BigDecimal weightKg;

    /** 本批目标定量；为空时按实测定量判一等 */
    private Integer targetGsm;

    @NotBlank(message = "产出经办人不能为空")
    @Size(max = 32, message = "经办人长度不能超过 32")
    private String operator;
}

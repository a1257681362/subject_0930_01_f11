package com.evops.coldchain.dto;

import java.math.BigDecimal;

import javax.validation.constraints.NotBlank;

/**
 * 退货处置决定请求（质量岗 QA 在完成检验后提交）：
 * RELEASE 放行 / DESTROY 销毁 / PENDING_HOLD 转待查。
 * 提交后退货单停留 PENDING_REVIEW，必须由不同于提交人的第二账号复核后才生效。
 */
public class ReturnDecisionRequest {
    @NotBlank(message = "处置决定不能为空（RELEASE 放行 / DESTROY 销毁 / PENDING_HOLD 转待查）")
    private String decision;
    @NotBlank(message = "处置依据不能为空：处理决定须说明检验结论与判定理由")
    private String decisionNote;
    @NotBlank(message = "处置目标库位不能为空：复核通过后退货批次将移动至该库位")
    private String targetLocation;
    /** 质检报告（质量人员完成检验的书面结论） */
    private String inspectReport;
    /** 实测温度 ℃（可空） */
    private BigDecimal measuredTemp;

    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }
    public String getDecisionNote() { return decisionNote; }
    public void setDecisionNote(String decisionNote) { this.decisionNote = decisionNote; }
    public String getTargetLocation() { return targetLocation; }
    public void setTargetLocation(String targetLocation) { this.targetLocation = targetLocation; }
    public String getInspectReport() { return inspectReport; }
    public void setInspectReport(String inspectReport) { this.inspectReport = inspectReport; }
    public BigDecimal getMeasuredTemp() { return measuredTemp; }
    public void setMeasuredTemp(BigDecimal measuredTemp) { this.measuredTemp = measuredTemp; }
}

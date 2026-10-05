package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 退货批次（V11）。
 * 一张退货单与一个原在途销售批次 1:1 锁定；建单即把原批次核对要素逐字段快照，
 * 原批次/药品档案事后撤回作废不影响退货单展示。状态机：
 * QUARANTINED 隔离中 -> INSPECTING 质检中 -> PENDING_REVIEW 待双人复核
 * -> RELEASED 放行 / DESTROYED 销毁 / PENDING_HOLD 转待查（可再提交决定）；
 * 复核驳回退回 INSPECTING。库位每次变化都有 t_cc_return_location_move 不可变行背书。
 */
@TableName("t_cc_return_batch")
public class ReturnBatch extends BaseEntity {
    private String returnNo;
    private Long batchId;

    // —— 原销售批次只读快照 ——
    private String batchNoSnap;
    private String orderNoSnap;
    private String approvalNoSnap;
    private BigDecimal tempMinSnap;
    private BigDecimal tempMaxSnap;
    private String packageSpecSnap;
    private String receiverOrgSnap;
    private Integer shipQtySnap;
    private Integer returnQty;

    // —— 标准化退货原因 ——
    /** REJECTION 收货拒签 / TEMP_EXCURSION 温度超限 / PACKAGE_DAMAGED 包装破损 */
    private String reasonType;
    private String reasonName;
    private String reasonDetail;
    /** 关联既有异常单（可空；类型须与退货原因相容） */
    private Long sourceExceptionId;

    // —— 状态机 ——
    private String status;

    // —— 库位 ——
    private String quarantineLocation;
    private String currentLocation;
    private String currentArea;

    // —— 质量检验 ——
    private Long inspectBy;
    private String inspectName;
    private LocalDateTime inspectTime;
    private String inspectReport;
    private BigDecimal measuredTemp;

    // —— 处置决定（最近一轮；完整链在 t_cc_return_decision_review）——
    private String decision;
    private Long decisionBy;
    private String decisionName;
    private LocalDateTime decisionTime;
    private String decisionNote;
    private String targetLocation;

    // —— 终态双人复核（最近一次同意）——
    private Long reviewBy;
    private String reviewName;
    private LocalDateTime reviewTime;
    private String reviewNote;

    public String getReturnNo() { return returnNo; }
    public void setReturnNo(String returnNo) { this.returnNo = returnNo; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public String getBatchNoSnap() { return batchNoSnap; }
    public void setBatchNoSnap(String batchNoSnap) { this.batchNoSnap = batchNoSnap; }
    public String getOrderNoSnap() { return orderNoSnap; }
    public void setOrderNoSnap(String orderNoSnap) { this.orderNoSnap = orderNoSnap; }
    public String getApprovalNoSnap() { return approvalNoSnap; }
    public void setApprovalNoSnap(String approvalNoSnap) { this.approvalNoSnap = approvalNoSnap; }
    public BigDecimal getTempMinSnap() { return tempMinSnap; }
    public void setTempMinSnap(BigDecimal tempMinSnap) { this.tempMinSnap = tempMinSnap; }
    public BigDecimal getTempMaxSnap() { return tempMaxSnap; }
    public void setTempMaxSnap(BigDecimal tempMaxSnap) { this.tempMaxSnap = tempMaxSnap; }
    public String getPackageSpecSnap() { return packageSpecSnap; }
    public void setPackageSpecSnap(String packageSpecSnap) { this.packageSpecSnap = packageSpecSnap; }
    public String getReceiverOrgSnap() { return receiverOrgSnap; }
    public void setReceiverOrgSnap(String receiverOrgSnap) { this.receiverOrgSnap = receiverOrgSnap; }
    public Integer getShipQtySnap() { return shipQtySnap; }
    public void setShipQtySnap(Integer shipQtySnap) { this.shipQtySnap = shipQtySnap; }
    public Integer getReturnQty() { return returnQty; }
    public void setReturnQty(Integer returnQty) { this.returnQty = returnQty; }
    public String getReasonType() { return reasonType; }
    public void setReasonType(String reasonType) { this.reasonType = reasonType; }
    public String getReasonName() { return reasonName; }
    public void setReasonName(String reasonName) { this.reasonName = reasonName; }
    public String getReasonDetail() { return reasonDetail; }
    public void setReasonDetail(String reasonDetail) { this.reasonDetail = reasonDetail; }
    public Long getSourceExceptionId() { return sourceExceptionId; }
    public void setSourceExceptionId(Long sourceExceptionId) { this.sourceExceptionId = sourceExceptionId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getQuarantineLocation() { return quarantineLocation; }
    public void setQuarantineLocation(String quarantineLocation) { this.quarantineLocation = quarantineLocation; }
    public String getCurrentLocation() { return currentLocation; }
    public void setCurrentLocation(String currentLocation) { this.currentLocation = currentLocation; }
    public String getCurrentArea() { return currentArea; }
    public void setCurrentArea(String currentArea) { this.currentArea = currentArea; }
    public Long getInspectBy() { return inspectBy; }
    public void setInspectBy(Long inspectBy) { this.inspectBy = inspectBy; }
    public String getInspectName() { return inspectName; }
    public void setInspectName(String inspectName) { this.inspectName = inspectName; }
    public LocalDateTime getInspectTime() { return inspectTime; }
    public void setInspectTime(LocalDateTime inspectTime) { this.inspectTime = inspectTime; }
    public String getInspectReport() { return inspectReport; }
    public void setInspectReport(String inspectReport) { this.inspectReport = inspectReport; }
    public BigDecimal getMeasuredTemp() { return measuredTemp; }
    public void setMeasuredTemp(BigDecimal measuredTemp) { this.measuredTemp = measuredTemp; }
    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }
    public Long getDecisionBy() { return decisionBy; }
    public void setDecisionBy(Long decisionBy) { this.decisionBy = decisionBy; }
    public String getDecisionName() { return decisionName; }
    public void setDecisionName(String decisionName) { this.decisionName = decisionName; }
    public LocalDateTime getDecisionTime() { return decisionTime; }
    public void setDecisionTime(LocalDateTime decisionTime) { this.decisionTime = decisionTime; }
    public String getDecisionNote() { return decisionNote; }
    public void setDecisionNote(String decisionNote) { this.decisionNote = decisionNote; }
    public String getTargetLocation() { return targetLocation; }
    public void setTargetLocation(String targetLocation) { this.targetLocation = targetLocation; }
    public Long getReviewBy() { return reviewBy; }
    public void setReviewBy(Long reviewBy) { this.reviewBy = reviewBy; }
    public String getReviewName() { return reviewName; }
    public void setReviewName(String reviewName) { this.reviewName = reviewName; }
    public LocalDateTime getReviewTime() { return reviewTime; }
    public void setReviewTime(LocalDateTime reviewTime) { this.reviewTime = reviewTime; }
    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String reviewNote) { this.reviewNote = reviewNote; }
}

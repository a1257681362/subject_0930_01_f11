package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.CreateOnlyEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 退货处置决定双人复核流水（V11，只追加）。
 * QA 每提交一次 放行/销毁/转待查 决定即插入一行（result=PENDING，决策与提交人快照不可改写）；
 * 第二账号复核同意 APPROVED 后处置生效、系统生成终态 FINAL 库位移动，
 * 驳回 REJECTED 退回质检中，历史意见逐行保留不覆盖。复核人账号必须不同于提交人。
 */
@TableName("t_cc_return_decision_review")
public class ReturnDecisionReview extends CreateOnlyEntity {
    private Long returnId;
    private Long batchId;
    /** 同一退货单第几轮决定，从 1 递增 */
    private Integer reviewSeq;

    // —— 提交当刻决策快照（永不改写）——
    /** RELEASE 放行 / DESTROY 销毁 / PENDING_HOLD 转待查 */
    private String decision;
    private String targetLocation;
    private String decisionNote;
    private String inspectReport;
    private BigDecimal measuredTemp;

    // —— 提议人（双人校验依据）——
    private String proposerAccount;
    private String proposerName;
    private LocalDateTime proposeTime;

    // —— 复核结果 ——
    /** PENDING 待复核 / APPROVED 同意 / REJECTED 驳回 */
    private String result;
    private String reviewerAccount;
    private String reviewerName;
    private LocalDateTime reviewTime;
    private String reviewNote;

    public Long getReturnId() { return returnId; }
    public void setReturnId(Long returnId) { this.returnId = returnId; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public Integer getReviewSeq() { return reviewSeq; }
    public void setReviewSeq(Integer reviewSeq) { this.reviewSeq = reviewSeq; }
    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }
    public String getTargetLocation() { return targetLocation; }
    public void setTargetLocation(String targetLocation) { this.targetLocation = targetLocation; }
    public String getDecisionNote() { return decisionNote; }
    public void setDecisionNote(String decisionNote) { this.decisionNote = decisionNote; }
    public String getInspectReport() { return inspectReport; }
    public void setInspectReport(String inspectReport) { this.inspectReport = inspectReport; }
    public BigDecimal getMeasuredTemp() { return measuredTemp; }
    public void setMeasuredTemp(BigDecimal measuredTemp) { this.measuredTemp = measuredTemp; }
    public String getProposerAccount() { return proposerAccount; }
    public void setProposerAccount(String proposerAccount) { this.proposerAccount = proposerAccount; }
    public String getProposerName() { return proposerName; }
    public void setProposerName(String proposerName) { this.proposerName = proposerName; }
    public LocalDateTime getProposeTime() { return proposeTime; }
    public void setProposeTime(LocalDateTime proposeTime) { this.proposeTime = proposeTime; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public String getReviewerAccount() { return reviewerAccount; }
    public void setReviewerAccount(String reviewerAccount) { this.reviewerAccount = reviewerAccount; }
    public String getReviewerName() { return reviewerName; }
    public void setReviewerName(String reviewerName) { this.reviewerName = reviewerName; }
    public LocalDateTime getReviewTime() { return reviewTime; }
    public void setReviewTime(LocalDateTime reviewTime) { this.reviewTime = reviewTime; }
    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String reviewNote) { this.reviewNote = reviewNote; }
}

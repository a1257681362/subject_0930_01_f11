package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 退货处置决定双人复核请求：同意（处置生效并系统生成终态库位移动）/ 驳回（退回质检中重新处置）。
 * 复核人必须是不同于决策提交人的登录账号；同意/驳回都必须填写复核意见。
 */
public class ReturnReviewRequest {
    @NotNull(message = "复核结论不能为空（true 同意 / false 驳回）")
    private Boolean approved;
    @NotBlank(message = "复核意见不能为空：同意或驳回都必须留痕")
    private String reviewNote;

    public Boolean getApproved() { return approved; }
    public void setApproved(Boolean approved) { this.approved = approved; }
    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String reviewNote) { this.reviewNote = reviewNote; }
}

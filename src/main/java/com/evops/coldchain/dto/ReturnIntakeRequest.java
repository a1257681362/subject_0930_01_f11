package com.evops.coldchain.dto;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 退货登记请求：对在途（SHIPPED/EXCEPTION）原销售批次登记退货并直入隔离库位。
 * 退货原因仅支持 REJECTION 收货拒签 / TEMP_EXCURSION 温度超限 / PACKAGE_DAMAGED 包装破损；
 * 支持部分退货（0 < returnQty <= 原批次发运数量）。
 */
public class ReturnIntakeRequest {
    @NotNull(message = "原销售运输批次ID不能为空")
    private Long batchId;
    @NotBlank(message = "退货原因不能为空（收货拒签 REJECTION / 温度超限 TEMP_EXCURSION / 包装破损 PACKAGE_DAMAGED）")
    private String reasonType;
    /** 退货情况说明 */
    private String reasonDetail;
    /** 关联既有异常单ID（可空；填写时异常类型须与退货原因相容且属于同一原批次） */
    private Long sourceExceptionId;
    @NotNull(message = "退货数量不能为空")
    @Min(value = 1, message = "退货数量必须大于0")
    private Integer returnQty;
    @NotBlank(message = "隔离库位不能为空：退货批次进入隔离库位后不能再次出库")
    private String quarantineLocation;

    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public String getReasonType() { return reasonType; }
    public void setReasonType(String reasonType) { this.reasonType = reasonType; }
    public String getReasonDetail() { return reasonDetail; }
    public void setReasonDetail(String reasonDetail) { this.reasonDetail = reasonDetail; }
    public Long getSourceExceptionId() { return sourceExceptionId; }
    public void setSourceExceptionId(Long sourceExceptionId) { this.sourceExceptionId = sourceExceptionId; }
    public Integer getReturnQty() { return returnQty; }
    public void setReturnQty(Integer returnQty) { this.returnQty = returnQty; }
    public String getQuarantineLocation() { return quarantineLocation; }
    public void setQuarantineLocation(String quarantineLocation) { this.quarantineLocation = quarantineLocation; }
}

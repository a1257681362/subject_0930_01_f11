package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;

/**
 * 退货批次隔离/质检区内库位移动请求。
 * 处理过程中只允许在隔离区 QUARANTINE 与质检区 INSPECTION 内移动；
 * 待复核期间冻结移动；放行/销毁/转待查的终态出库只能由复核通过后系统生成。
 */
public class ReturnMoveRequest {
    @NotBlank(message = "目标库位不能为空")
    private String toLocation;
    @NotBlank(message = "目标库区不能为空（QUARANTINE 隔离区 / INSPECTION 质检区）")
    private String area;
    /** 移动原因 */
    private String reason;

    public String getToLocation() { return toLocation; }
    public void setToLocation(String toLocation) { this.toLocation = toLocation; }
    public String getArea() { return area; }
    public void setArea(String area) { this.area = area; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}

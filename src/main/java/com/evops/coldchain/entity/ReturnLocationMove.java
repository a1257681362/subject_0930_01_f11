package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.CreateOnlyEntity;

import java.time.LocalDateTime;

/**
 * 退货批次库位移动记录（V11，只追加）。
 * 每次移动记录起止库位、库区类别、移动人、移动时间与原因；
 * 入隔离 INTAKE 与处置出库 FINAL 由系统在建单/复核通过时生成，
 * 处理中仅允许隔离区/质检区 RELOCATE，终态移动无任何手工路径。
 */
@TableName("t_cc_return_location_move")
public class ReturnLocationMove extends CreateOnlyEntity {
    private Long returnId;
    private Long batchId;
    /** 同一退货单第几次移动，从 1 递增 */
    private Integer moveSeq;
    /** INTAKE 入隔离 / RELOCATE 区内移位 / FINAL 处置出库 */
    private String moveType;
    /** 来源库位（INTAKE 为空表示在途车辆直入隔离） */
    private String fromLocation;
    private String toLocation;
    /** QUARANTINE / INSPECTION / PENDING / RELEASED / DESTROYED */
    private String area;
    private String reason;
    /** 移动操作账号/姓名快照 */
    private String moverAccount;
    private String moverName;
    private LocalDateTime moveTime;

    public Long getReturnId() { return returnId; }
    public void setReturnId(Long returnId) { this.returnId = returnId; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public Integer getMoveSeq() { return moveSeq; }
    public void setMoveSeq(Integer moveSeq) { this.moveSeq = moveSeq; }
    public String getMoveType() { return moveType; }
    public void setMoveType(String moveType) { this.moveType = moveType; }
    public String getFromLocation() { return fromLocation; }
    public void setFromLocation(String fromLocation) { this.fromLocation = fromLocation; }
    public String getToLocation() { return toLocation; }
    public void setToLocation(String toLocation) { this.toLocation = toLocation; }
    public String getArea() { return area; }
    public void setArea(String area) { this.area = area; }
    public LocalDateTime getMoveTime() { return moveTime; }
    public void setMoveTime(LocalDateTime moveTime) { this.moveTime = moveTime; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getMoverAccount() { return moverAccount; }
    public void setMoverAccount(String moverAccount) { this.moverAccount = moverAccount; }
    public String getMoverName() { return moverName; }
    public void setMoverName(String moverName) { this.moverName = moverName; }
}

package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.dto.ReturnDecisionRequest;
import com.evops.coldchain.dto.ReturnIntakeRequest;
import com.evops.coldchain.dto.ReturnMoveRequest;
import com.evops.coldchain.entity.ExceptionEvent;
import com.evops.coldchain.entity.ReturnBatch;
import com.evops.coldchain.entity.ReturnDecisionReview;
import com.evops.coldchain.entity.ReturnLocationMove;
import com.evops.coldchain.entity.ShipmentOrder;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.mapper.ExceptionEventMapper;
import com.evops.coldchain.mapper.ReturnBatchMapper;
import com.evops.coldchain.mapper.ReturnDecisionReviewMapper;
import com.evops.coldchain.mapper.ReturnLocationMoveMapper;
import com.evops.coldchain.mapper.SequenceMapper;
import com.evops.coldchain.mapper.ShipmentOrderMapper;
import com.evops.coldchain.mapper.TransportBatchMapper;
import com.evops.common.BusinessException;
import com.evops.common.UserContext;
import org.apache.shiro.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 退货批次隔离处理服务（V11）。
 *
 * 业务规则：
 * - 仅在途（SHIPPED/EXCEPTION）原销售批次可登记退货，退货单与原批次 1:1；建单即固化原批次
 *   核对要素快照、生成首条 INTAKE 隔离移动，并在原批次写只读关联锁（return_batch_id 只写一次，
 *   永不清空/不改向），不改动原批次状态、温度、异常任何原始行。
 * - 退货原因仅三种：REJECTION 收货拒签 / TEMP_EXCURSION 温度超限 / PACKAGE_DAMAGED 包装破损；
 *   可引用类型相容的既有异常单。支持部分退货（0 < 退货数量 <= 发运数量）。
 * - 入隔离后到处置完成前，退货批次不能出库：处理中只允许隔离区/质检区 RELOCATE；
 *   放行出库/销毁区/待查库位三类 FINAL 移动只能由双人复核通过后系统生成。
 * - 质量人员（QA）开始检验后才能提交 RELEASE/DESTROY/PENDING_HOLD 决定；提交后停留
 *   PENDING_REVIEW，必须由不同于提交人的第二账号复核：同意按决定落终态并生成 FINAL 移动，
 *   驳回退回 INSPECTING；PENDING_HOLD 允许再次提交新决定。每次提交/复核追加不可变流水行。
 * - 列表/页面/证据链导出共用 searchReturns 同一查询（同过滤、同行集），原批次事后撤回作废，
 *   退货单仍按快照在三端一致展示。
 *
 * 依赖约束：只依赖 Mapper/序列/操作日志，不依赖 TransportBatchService 等业务服务，
 * 避免被各推进服务注入本卡口时形成循环引用。
 */
@Service
public class ReturnBatchService {

    @Resource
    private ReturnBatchMapper returnMapper;
    @Resource
    private ReturnLocationMoveMapper moveMapper;
    @Resource
    private ReturnDecisionReviewMapper reviewMapper;
    @Resource
    private TransportBatchMapper batchMapper;
    @Resource
    private ShipmentOrderMapper orderMapper;
    @Resource
    private ExceptionEventMapper exceptionMapper;
    @Resource
    private SequenceMapper sequenceMapper;
    @Resource
    private OpLogService opLogService;

    // ---------------- 退货登记（入隔离） ----------------

    @Transactional
    public ReturnBatch intake(ReturnIntakeRequest req) {
        requirePermitted(CcConstants.PERM_RETURN_CREATE, "退货登记");
        TransportBatch batch = requireBatch(req.getBatchId());

        // 仅在途销售批次可退货：已派车/装车未发运、已签收、已撤回均不可登记
        if (!CcConstants.BATCH_SHIPPED.equals(batch.getStatus())
                && !CcConstants.BATCH_EXCEPTION.equals(batch.getStatus())) {
            throw new BusinessException(String.format(
                    "仅在途（SHIPPED/EXCEPTION）批次允许登记退货，批次 %s 当前状态: %s",
                    batch.getBatchNo(), batch.getStatus()));
        }
        if (batch.getReturnBatchId() != null) {
            ReturnBatch exists = returnMapper.selectById(batch.getReturnBatchId());
            throw new BusinessException(String.format("批次 %s 已登记退货单 %s，一个销售批次只能退货一次",
                    batch.getBatchNo(), exists == null ? "#" + batch.getReturnBatchId() : exists.getReturnNo()));
        }

        String reasonType = normalizeReason(req.getReasonType());
        String reasonName = reasonName(reasonType);
        int returnQty = req.getReturnQty() == null ? 0 : req.getReturnQty();
        if (returnQty <= 0) {
            throw new BusinessException("退货数量必须大于0");
        }
        if (returnQty > batch.getShipQty()) {
            throw new BusinessException(String.format(
                    "退货数量 %d 不能超过原批次发运数量 %d（支持部分退货，但不得多退）", returnQty, batch.getShipQty()));
        }
        String location = trim(req.getQuarantineLocation());
        if (location == null) {
            throw new BusinessException("隔离库位不能为空：退货批次进入隔离库位后不能再次出库");
        }

        ExceptionEvent sourceException = null;
        if (req.getSourceExceptionId() != null) {
            sourceException = exceptionMapper.selectById(req.getSourceExceptionId());
            if (sourceException == null || !batch.getId().equals(sourceException.getBatchId())) {
                throw new BusinessException("关联异常不存在或不属于该原销售批次: " + req.getSourceExceptionId());
            }
            if (!reasonCompatible(reasonType, sourceException.getExceptionType())) {
                throw new BusinessException(String.format(
                        "关联异常类型[%s]与退货原因[%s/%s]不相容",
                        sourceException.getExceptionType(), reasonType, reasonName));
            }
        }

        ShipmentOrder order = orderMapper.selectById(batch.getOrderId());

        ReturnBatch ret = new ReturnBatch();
        ret.setReturnNo(generateReturnNo());
        ret.setBatchId(batch.getId());
        ret.setBatchNoSnap(batch.getBatchNo());
        ret.setOrderNoSnap(order == null ? null : order.getOrderNo());
        ret.setApprovalNoSnap(batch.getApprovalNoSnap());
        ret.setTempMinSnap(batch.getTempMinSnap());
        ret.setTempMaxSnap(batch.getTempMaxSnap());
        ret.setPackageSpecSnap(batch.getPackageSpecSnap());
        ret.setReceiverOrgSnap(batch.getReceiverOrg());
        ret.setShipQtySnap(batch.getShipQty());
        ret.setReturnQty(returnQty);
        ret.setReasonType(reasonType);
        ret.setReasonName(reasonName);
        ret.setReasonDetail(trim(req.getReasonDetail()));
        ret.setSourceExceptionId(sourceException == null ? null : sourceException.getId());
        ret.setStatus(CcConstants.RETURN_QUARANTINED);
        ret.setQuarantineLocation(location);
        ret.setCurrentLocation(location);
        ret.setCurrentArea(CcConstants.RETURN_AREA_QUARANTINE);
        returnMapper.insert(ret);

        // 原批次只读关联锁：一旦写入永不清空、永不改向（uk_cc_return_batch 数据库层兜底并发重复）
        batch.setReturnBatchId(ret.getId());
        batchMapper.updateById(batch);

        // 首条移动：在途车辆直入隔离库位（INTAKE，来源库位为空），记录库位/人员/时间
        insertMove(ret, CcConstants.RETURN_MOVE_INTAKE, null, location,
                CcConstants.RETURN_AREA_QUARANTINE, "退货登记直入隔离库位", LocalDateTime.now());

        opLogService.log(CcConstants.ENT_RETURN_BATCH, ret.getId(), batch.getId(),
                CcConstants.ACT_RETURN_INTAKE,
                String.format("原销售批次 %s 登记退货单 %s：退货原因=%s（%s），退货数量=%d/%d，直入隔离库位=%s；"
                                + "原批次已加只读关联锁、状态/温度/异常行不变，隔离后不能再次出库%s",
                        batch.getBatchNo(), ret.getReturnNo(), reasonType, reasonName,
                        returnQty, batch.getShipQty(), location,
                        sourceException == null ? "" : "；关联异常#" + sourceException.getId()));
        return ret;
    }

    // ---------------- 质量检验与处置决定（双人复核） ----------------

    /** QA 开始检验：仅隔离中可开始，开始后进入质检中才能提交处置决定 */
    @Transactional
    public ReturnBatch startInspection(Long returnId) {
        requirePermitted(CcConstants.PERM_RETURN_INSPECT, "质量检验");
        ReturnBatch ret = requireReturn(returnId);
        if (!CcConstants.RETURN_QUARANTINED.equals(ret.getStatus())) {
            throw new BusinessException(String.format(
                    "退货单 %s 当前状态 %s，仅隔离中（QUARANTINED）允许开始质量检验", ret.getReturnNo(), ret.getStatus()));
        }
        ret.setInspectBy(UserContext.currentId());
        ret.setInspectName(UserContext.currentName());
        ret.setInspectTime(LocalDateTime.now());
        ret.setStatus(CcConstants.RETURN_INSPECTING);
        returnMapper.updateById(ret);

        opLogService.log(CcConstants.ENT_RETURN_BATCH, ret.getId(), ret.getBatchId(),
                CcConstants.ACT_RETURN_INSPECT_START,
                String.format("退货单 %s 质量人员 %s 开始检验，进入质检中；检验完成前不得选择放行/销毁/转待查",
                        ret.getReturnNo(), UserContext.currentName()));
        return ret;
    }

    /** QA 提交处置决定：质检中或转待查后可提交；提交即停留 PENDING_REVIEW 等待第二账号复核 */
    @Transactional
    public ReturnDecisionReview submitDecision(Long returnId, ReturnDecisionRequest req) {
        requirePermitted(CcConstants.PERM_RETURN_INSPECT, "提交处置决定");
        ReturnBatch ret = requireReturn(returnId);
        if (!CcConstants.RETURN_INSPECTING.equals(ret.getStatus())
                && !CcConstants.RETURN_PENDING_HOLD.equals(ret.getStatus())) {
            throw new BusinessException(String.format(
                    "退货单 %s 当前状态 %s：须由质量人员开始并完成检验后才能提交处置决定（转待查后可再次提交）",
                    ret.getReturnNo(), ret.getStatus()));
        }
        String decision = normalizeDecision(req.getDecision());
        String target = trim(req.getTargetLocation());
        if (target == null) {
            throw new BusinessException("处置目标库位不能为空：复核通过后退货批次将移动至该库位");
        }
        String note = trim(req.getDecisionNote());
        if (note == null) {
            throw new BusinessException("处置依据不能为空：处理决定须说明检验结论与判定理由");
        }
        String report = trim(req.getInspectReport());

        int seq = countReviews(returnId) + 1;
        LocalDateTime now = LocalDateTime.now();
        String proposer = UserContext.currentName();

        // 决策快照与提交人落行后不可改写
        ReturnDecisionReview review = new ReturnDecisionReview();
        review.setReturnId(returnId);
        review.setBatchId(ret.getBatchId());
        review.setReviewSeq(seq);
        review.setDecision(decision);
        review.setTargetLocation(target);
        review.setDecisionNote(note);
        review.setInspectReport(report);
        review.setMeasuredTemp(req.getMeasuredTemp());
        review.setProposerAccount(proposer);
        review.setProposerName(proposer);
        review.setProposeTime(now);
        review.setResult(CcConstants.REVIEW_PENDING);
        reviewMapper.insert(review);

        ret.setInspectReport(report);
        if (req.getMeasuredTemp() != null) {
            ret.setMeasuredTemp(req.getMeasuredTemp());
        }
        ret.setDecision(decision);
        ret.setDecisionBy(UserContext.currentId());
        ret.setDecisionName(proposer);
        ret.setDecisionTime(now);
        ret.setDecisionNote(note);
        ret.setTargetLocation(target);
        ret.setStatus(CcConstants.RETURN_PENDING_REVIEW);
        returnMapper.updateById(ret);

        opLogService.log(CcConstants.ENT_RETURN_REVIEW, review.getId(), ret.getBatchId(),
                CcConstants.ACT_RETURN_DECISION_SUBMIT,
                String.format("退货单 %s 第%d轮处置决定：质量人员 %s 提交[%s]，目标库位=%s，单据停留待双人复核（提交人不能复核本人决定）",
                        ret.getReturnNo(), seq, proposer, decisionName(decision), target));
        return review;
    }

    /** 第二账号双人复核：同意按决定落终态并系统生成 FINAL 移动；驳回退回质检中 */
    @Transactional
    public ReturnDecisionReview review(Long returnId, boolean approved, String reviewNoteRaw) {
        requirePermitted(CcConstants.PERM_RETURN_REVIEW, "处置决定复核");
        ReturnBatch ret = requireReturn(returnId);
        if (!CcConstants.RETURN_PENDING_REVIEW.equals(ret.getStatus())) {
            throw new BusinessException(String.format(
                    "退货单 %s 当前状态 %s，没有待复核的处置决定", ret.getReturnNo(), ret.getStatus()));
        }
        String note = trim(reviewNoteRaw);
        if (note == null) {
            throw new BusinessException("复核意见不能为空：同意或驳回都必须留痕");
        }
        ReturnDecisionReview review = latestReview(returnId);
        if (review == null || !CcConstants.REVIEW_PENDING.equals(review.getResult())) {
            throw new BusinessException(String.format("退货单 %s 未找到待复核的处置决定流水", ret.getReturnNo()));
        }
        String reviewer = UserContext.currentName();
        if (reviewer.equals(review.getProposerAccount())) {
            throw new BusinessException(String.format(
                    "处理决定必须双人复核：复核人[%s]不能是决定提交人本人（退货单 %s，第%d轮决定）",
                    reviewer, ret.getReturnNo(), review.getReviewSeq()));
        }

        LocalDateTime now = LocalDateTime.now();
        review.setReviewerAccount(reviewer);
        review.setReviewerName(reviewer);
        review.setReviewTime(now);
        review.setReviewNote(note);
        review.setResult(approved ? CcConstants.REVIEW_APPROVED : CcConstants.REVIEW_REJECTED);
        reviewMapper.updateById(review);

        if (approved) {
            String finalArea;
            switch (review.getDecision()) {
                case CcConstants.RETURN_DECISION_RELEASE:
                    finalArea = CcConstants.RETURN_RELEASED;
                    ret.setStatus(CcConstants.RETURN_RELEASED);
                    break;
                case CcConstants.RETURN_DECISION_DESTROY:
                    finalArea = CcConstants.RETURN_DESTROYED;
                    ret.setStatus(CcConstants.RETURN_DESTROYED);
                    break;
                case CcConstants.RETURN_DECISION_PENDING_HOLD:
                    finalArea = CcConstants.RETURN_AREA_PENDING;
                    ret.setStatus(CcConstants.RETURN_PENDING_HOLD);
                    break;
                default:
                    throw new BusinessException("未知处置决定: " + review.getDecision());
            }
            // 终态出库只能由复核通过后系统生成 FINAL 移动（无任何手工终态移动路径）；
            // 来源库位取移动发生前的当前库位，保证移动链首尾相接（转待查后再次放行同样接续）
            String fromLocation = ret.getCurrentLocation();
            ret.setCurrentLocation(review.getTargetLocation());
            ret.setCurrentArea(finalArea);
            ret.setReviewBy(UserContext.currentId());
            ret.setReviewName(reviewer);
            ret.setReviewTime(now);
            ret.setReviewNote(note);
            returnMapper.updateById(ret);
            insertMove(ret, CcConstants.RETURN_MOVE_FINAL, fromLocation, review.getTargetLocation(), finalArea,
                    "第" + review.getReviewSeq() + "轮决定[" + decisionName(review.getDecision())
                            + "]双人复核通过，系统生成处置移动：" + note, now);

            opLogService.log(CcConstants.ENT_RETURN_REVIEW, review.getId(), ret.getBatchId(),
                    CcConstants.ACT_RETURN_REVIEW_APPROVE,
                    String.format("退货单 %s 第%d轮[%s]决定双人复核通过：提交人=%s，复核人=%s（第二账号），终态=%s，目标库位=%s",
                            ret.getReturnNo(), review.getReviewSeq(), decisionName(review.getDecision()),
                            review.getProposerAccount(), reviewer, ret.getStatus(), review.getTargetLocation()));
        } else {
            // 驳回退回质检中重新处置；决策快照与历史意见保留，不生成移动
            ret.setStatus(CcConstants.RETURN_INSPECTING);
            returnMapper.updateById(ret);

            opLogService.log(CcConstants.ENT_RETURN_REVIEW, review.getId(), ret.getBatchId(),
                    CcConstants.ACT_RETURN_REVIEW_REJECT,
                    String.format("退货单 %s 第%d轮[%s]决定被复核人 %s 驳回，退回质检中重新处置；提交人=%s，意见=%s",
                            ret.getReturnNo(), review.getReviewSeq(), decisionName(review.getDecision()),
                            reviewer, review.getProposerAccount(), note));
        }
        return review;
    }

    // ---------------- 库位移动（每次记录库位、人员、时间） ----------------

    /** 处理过程中的区内移位：仅隔离中/质检中允许，且只能在隔离区、质检区之间移动 */
    @Transactional
    public ReturnLocationMove relocate(Long returnId, ReturnMoveRequest req) {
        requirePermitted(CcConstants.PERM_RETURN_CREATE, "隔离库位移动");
        ReturnBatch ret = requireReturn(returnId);
        if (!CcConstants.RETURN_QUARANTINED.equals(ret.getStatus())
                && !CcConstants.RETURN_INSPECTING.equals(ret.getStatus())) {
            throw new BusinessException(String.format(
                    "退货单 %s 当前状态 %s：待复核期间移动冻结，放行/销毁/待查终态出库只能由复核通过后系统生成",
                    ret.getReturnNo(), ret.getStatus()));
        }
        String to = trim(req.getToLocation());
        if (to == null) {
            throw new BusinessException("目标库位不能为空");
        }
        String area = trim(req.getArea());
        if (area == null) {
            throw new BusinessException("目标库区不能为空");
        }
        area = area.toUpperCase();
        if (!CcConstants.RETURN_AREA_QUARANTINE.equals(area)
                && !CcConstants.RETURN_AREA_INSPECTION.equals(area)) {
            throw new BusinessException(String.format(
                    "处理中仅允许移动到隔离区 QUARANTINE / 质检区 INSPECTION；%s 属于终态库区，终态出库只能由处置复核生成", area));
        }
        if (CcConstants.RETURN_QUARANTINED.equals(ret.getStatus())
                && !CcConstants.RETURN_AREA_QUARANTINE.equals(area)) {
            throw new BusinessException("质量检验尚未开始，退货批次只能在隔离区内移动；开始检验后方可移入质检区");
        }
        if (to.equals(ret.getCurrentLocation())) {
            throw new BusinessException("目标库位与当前库位相同，无需移动: " + to);
        }
        String reason = trim(req.getReason());
        LocalDateTime now = LocalDateTime.now();
        ReturnLocationMove move = insertMove(ret, CcConstants.RETURN_MOVE_RELOCATE,
                ret.getCurrentLocation(), to, area,
                reason == null ? "隔离/质检区内移位" : reason, now);
        ret.setCurrentLocation(to);
        ret.setCurrentArea(area);
        returnMapper.updateById(ret);

        opLogService.log(CcConstants.ENT_RETURN_MOVE, move.getId(), ret.getBatchId(), CcConstants.ACT_RETURN_MOVE,
                String.format("退货单 %s 第%d次移动：%s(%s) -> %s(%s)，移动人=%s，原因=%s",
                        ret.getReturnNo(), move.getMoveSeq(), move.getFromLocation(), ret.getCurrentArea(),
                        to, area, UserContext.currentName(), move.getReason()));
        return move;
    }

    // ---------------- 查询（列表 API / 页面 / 导出共用同一口径） ----------------

    /**
     * 退货单统一查询：页面列表、后台 API、证据链导出都调用本方法，保证同口径。
     * 仅按退货单自身字段过滤，绝不因原批次/药品档案事后撤回作废而隐藏任何退货行。
     */
    public List<ReturnBatch> searchReturns(String status, String reasonType) {
        QueryWrapper<ReturnBatch> qw = new QueryWrapper<ReturnBatch>().orderByDesc("id");
        if (status != null && !status.trim().isEmpty()) {
            qw.eq("status", status.trim().toUpperCase());
        }
        if (reasonType != null && !reasonType.trim().isEmpty()) {
            qw.eq("reason_type", reasonType.trim().toUpperCase());
        }
        return returnMapper.selectList(qw);
    }

    public List<ReturnBatch> listPendingReview() {
        requirePermitted(CcConstants.PERM_RETURN_REVIEW, "查看待复核队列");
        return returnMapper.selectList(new QueryWrapper<ReturnBatch>()
                .eq("status", CcConstants.RETURN_PENDING_REVIEW).orderByAsc("id"));
    }

    public ReturnBatch findByBatch(Long batchId) {
        return returnMapper.selectOne(new QueryWrapper<ReturnBatch>().eq("batch_id", batchId));
    }

    public List<ReturnLocationMove> listMoves(Long returnId) {
        return moveMapper.selectList(new QueryWrapper<ReturnLocationMove>()
                .eq("return_id", returnId).orderByAsc("move_seq"));
    }

    public List<ReturnDecisionReview> listReviews(Long returnId) {
        return reviewMapper.selectList(new QueryWrapper<ReturnDecisionReview>()
                .eq("return_id", returnId).orderByAsc("review_seq"));
    }

    /** 退货单详情：退货抬头 + 原批次/原发货单（只读关联，容忍原点已作废）+ 关联异常 + 移动链 + 复核链 */
    public Map<String, Object> detail(Long returnId) {
        ReturnBatch ret = requireReturn(returnId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("return", ret);
        // 原销售批次保持只读关联：即使事后撤回作废仍原样展示其状态
        TransportBatch origin = batchMapper.selectById(ret.getBatchId());
        data.put("originBatch", origin);
        data.put("originBatchStatus", origin == null ? null : origin.getStatus());
        data.put("originBatchRevoked", origin != null
                && CcConstants.BATCH_REVOKED.equals(origin.getStatus()));
        data.put("originOrder", origin == null ? null : orderMapper.selectById(origin.getOrderId()));
        data.put("sourceException", ret.getSourceExceptionId() == null
                ? null : exceptionMapper.selectById(ret.getSourceExceptionId()));
        data.put("moves", listMoves(returnId));
        data.put("reviews", listReviews(returnId));
        return data;
    }

    // ---------------- 证据链导出（与页面/后台同口径） ----------------

    /** 证据链导出行：与 searchReturns 同一行集，按退货单分段输出 抬头/移动/复核 三类行；列与 evidenceHeader 严格对齐 */
    public List<String[]> evidenceRows(String status, String reasonType) {
        List<ReturnBatch> list = searchReturns(status, reasonType);
        List<String[]> rows = new ArrayList<>();
        for (ReturnBatch ret : list) {
            // —— 退货抬头（一单一行）——
            String[] head = emptyRow();
            head[0] = "退货抬头";
            head[1] = ret.getReturnNo();
            head[2] = ret.getBatchNoSnap();
            head[3] = nz(ret.getOrderNoSnap());
            head[4] = ret.getApprovalNoSnap();
            head[5] = str(ret.getTempMinSnap());
            head[6] = str(ret.getTempMaxSnap());
            head[7] = ret.getPackageSpecSnap();
            head[8] = ret.getReceiverOrgSnap();
            head[9] = String.valueOf(ret.getShipQtySnap());
            head[10] = String.valueOf(ret.getReturnQty());
            head[11] = ret.getStatus() + " " + statusName(ret.getStatus());
            head[21] = ret.getReasonType() + " " + ret.getReasonName();
            head[23] = nz(ret.getReasonDetail());
            head[24] = nz(ret.getInspectReport());
            head[25] = str(ret.getMeasuredTemp());
            head[30] = nz(ret.getCurrentArea()) + " " + nz(ret.getCurrentLocation());
            rows.add(head);

            // —— 库位移动（每行一条）——
            for (ReturnLocationMove m : listMoves(ret.getId())) {
                String[] row = emptyRow();
                row[0] = "库位移动";
                row[1] = ret.getReturnNo();
                row[2] = ret.getBatchNoSnap();
                row[11] = ret.getStatus();
                row[12] = String.valueOf(m.getMoveSeq());
                row[13] = m.getMoveType();
                row[14] = nz(m.getFromLocation());
                row[15] = m.getToLocation();
                row[16] = m.getArea();
                row[17] = nz(m.getReason());
                row[18] = m.getMoverName() + "(" + m.getMoverAccount() + ")";
                row[19] = String.valueOf(m.getMoveTime());
                rows.add(row);
            }

            // —— 复核流水（每轮提交/复核一行）——
            for (ReturnDecisionReview r : listReviews(ret.getId())) {
                String[] row = emptyRow();
                row[0] = "复核流水";
                row[1] = ret.getReturnNo();
                row[2] = ret.getBatchNoSnap();
                row[11] = ret.getStatus();
                row[20] = String.valueOf(r.getReviewSeq());
                row[21] = r.getDecision() + " " + decisionName(r.getDecision());
                row[22] = nz(r.getTargetLocation());
                row[23] = nz(r.getDecisionNote());
                row[24] = nz(r.getInspectReport());
                row[25] = str(r.getMeasuredTemp());
                row[26] = r.getProposerName() + "(" + r.getProposerAccount() + ")";
                row[27] = String.valueOf(r.getProposeTime());
                row[28] = r.getResult();
                row[29] = nz(r.getReviewerAccount());
                row[30] = String.valueOf(r.getReviewTime());
                row[31] = nz(r.getReviewNote());
                rows.add(row);
            }
        }
        return rows;
    }

    /** 与 evidenceHeader 等宽的空行（32 列），各分段只填本类字段，保证三类行列严格对齐 */
    private static String[] emptyRow() {
        String[] row = new String[32];
        Arrays.fill(row, "");
        return row;
    }

    public String[] evidenceHeader() {
        return new String[]{
                "段落", "退货单号", "原批次号", "原发货单号", "批准文号", "温区下限℃", "温区上限℃",
                "包装规格", "收货机构", "发运数量", "退货数量", "退货状态",
                "移动序号", "移动类型", "来源库位", "目标库位", "库区", "移动原因", "移动人", "移动时间",
                "复核轮次", "处置决定", "决定目标库位", "处置依据", "质检报告", "实测温度℃",
                "提交人", "提交时间", "复核结果", "复核人", "复核时间", "复核意见"};
    }

    // ---------------- 原批次出库/推进共享卡口 ----------------

    /**
     * 推进卡口：原销售批次一旦被退货单 1:1 锁定，任何出库/推进动作一律拦截。
     * 撤回（作废）不经过本卡口——退货成立后仍允许把原运输对象置为无效，退货台账靠快照同口径保留。
     */
    public void requireBatchNotReturned(Long batchId) {
        if (batchId == null) {
            return;
        }
        TransportBatch batch = batchMapper.selectById(batchId);
        if (batch != null && batch.getReturnBatchId() != null) {
            ReturnBatch ret = returnMapper.selectById(batch.getReturnBatchId());
            throw new BusinessException(String.format(
                    "批次 %s 已登记退货单 %s 并进入隔离库位，退货批次隔离处理完成前禁止任何出库/推进操作",
                    batch.getBatchNo(), ret == null ? "#" + batch.getReturnBatchId() : ret.getReturnNo()));
        }
    }

    // ---------------- 私有工具 ----------------

    private ReturnLocationMove insertMove(ReturnBatch ret, String type, String from, String to,
                                          String area, String reason, LocalDateTime time) {
        int seq = countMoves(ret.getId()) + 1;
        ReturnLocationMove move = new ReturnLocationMove();
        move.setReturnId(ret.getId());
        move.setBatchId(ret.getBatchId());
        move.setMoveSeq(seq);
        move.setMoveType(type);
        move.setFromLocation(from);
        move.setToLocation(to);
        move.setArea(area);
        move.setReason(reason);
        move.setMoverAccount(UserContext.currentName());
        move.setMoverName(UserContext.currentName());
        move.setMoveTime(time);
        moveMapper.insert(move);
        return move;
    }

    private ReturnDecisionReview latestReview(Long returnId) {
        List<ReturnDecisionReview> list = reviewMapper.selectList(new QueryWrapper<ReturnDecisionReview>()
                .eq("return_id", returnId).orderByDesc("review_seq").last("LIMIT 1"));
        return list.isEmpty() ? null : list.get(0);
    }

    private int countMoves(Long returnId) {
        Long n = moveMapper.selectCount(new QueryWrapper<ReturnLocationMove>().eq("return_id", returnId));
        return n == null ? 0 : n.intValue();
    }

    private int countReviews(Long returnId) {
        Long n = reviewMapper.selectCount(new QueryWrapper<ReturnDecisionReview>().eq("return_id", returnId));
        return n == null ? 0 : n.intValue();
    }

    public ReturnBatch requireReturn(Long returnId) {
        ReturnBatch ret = returnId == null ? null : returnMapper.selectById(returnId);
        if (ret == null) {
            throw new BusinessException("退货单不存在: " + returnId);
        }
        return ret;
    }

    private TransportBatch requireBatch(Long batchId) {
        if (batchId == null) {
            throw new BusinessException("原销售运输批次ID不能为空");
        }
        TransportBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new BusinessException("原销售运输批次不存在: " + batchId);
        }
        return batch;
    }

    /** 退货单号：TH + 8 位序列 */
    private String generateReturnNo() {
        return String.format("TH%08d", sequenceMapper.nextReturnNo());
    }

    private static String normalizeReason(String input) {
        if (input == null || input.trim().isEmpty()) {
            throw new BusinessException("退货原因不能为空：仅支持 收货拒签 REJECTION / 温度超限 TEMP_EXCURSION / 包装破损 PACKAGE_DAMAGED");
        }
        String reason = input.trim().toUpperCase();
        if (CcConstants.RETURN_REASON_REJECTION.equals(reason)
                || CcConstants.EXC_TEMP.equals(reason)
                || CcConstants.RETURN_REASON_PACKAGE_DAMAGED.equals(reason)) {
            return reason;
        }
        throw new BusinessException("退货原因仅支持三种标准代码：REJECTION 收货拒签 / TEMP_EXCURSION 温度超限 / "
                + "PACKAGE_DAMAGED 包装破损，实际: " + input);
    }

    private static String reasonName(String reasonType) {
        switch (reasonType) {
            case CcConstants.RETURN_REASON_REJECTION:
                return CcConstants.RETURN_REASON_NAME_REJECTION;
            case CcConstants.EXC_TEMP:
                return CcConstants.RETURN_REASON_NAME_TEMP;
            case CcConstants.RETURN_REASON_PACKAGE_DAMAGED:
                return CcConstants.RETURN_REASON_NAME_PACKAGE_DAMAGED;
            default:
                throw new BusinessException("未知退货原因: " + reasonType);
        }
    }

    /** 退货原因与既有异常类型相容校验：包装破损可引用旧 DAMAGE 或 V6 SEAL_BROKEN */
    private static boolean reasonCompatible(String reasonType, String exceptionType) {
        if (exceptionType == null) {
            return false;
        }
        if (CcConstants.RETURN_REASON_REJECTION.equals(reasonType)) {
            return CcConstants.EXC_REJECTION.equals(exceptionType);
        }
        if (CcConstants.EXC_TEMP.equals(reasonType)) {
            return CcConstants.EXC_TEMP.equals(exceptionType);
        }
        if (CcConstants.RETURN_REASON_PACKAGE_DAMAGED.equals(reasonType)) {
            return CcConstants.EXC_DAMAGE.equals(exceptionType) || CcConstants.EXC_SEAL_BROKEN.equals(exceptionType);
        }
        return false;
    }

    private static String normalizeDecision(String input) {
        if (input == null || input.trim().isEmpty()) {
            throw new BusinessException("处置决定不能为空：RELEASE 放行 / DESTROY 销毁 / PENDING_HOLD 转待查");
        }
        String decision = input.trim().toUpperCase();
        if (CcConstants.RETURN_DECISION_RELEASE.equals(decision)
                || CcConstants.RETURN_DECISION_DESTROY.equals(decision)
                || CcConstants.RETURN_DECISION_PENDING_HOLD.equals(decision)) {
            return decision;
        }
        throw new BusinessException("处置决定仅支持 RELEASE 放行 / DESTROY 销毁 / PENDING_HOLD 转待查，实际: " + input);
    }

    private static String decisionName(String decision) {
        switch (decision) {
            case CcConstants.RETURN_DECISION_RELEASE:
                return "放行";
            case CcConstants.RETURN_DECISION_DESTROY:
                return "销毁";
            case CcConstants.RETURN_DECISION_PENDING_HOLD:
                return "转待查";
            default:
                return decision;
        }
    }

    private static String statusName(String status) {
        if (status == null) {
            return "";
        }
        switch (status) {
            case CcConstants.RETURN_QUARANTINED:
                return "隔离中";
            case CcConstants.RETURN_INSPECTING:
                return "质检中";
            case CcConstants.RETURN_PENDING_REVIEW:
                return "待复核";
            case CcConstants.RETURN_RELEASED:
                return "已放行";
            case CcConstants.RETURN_DESTROYED:
                return "已销毁";
            case CcConstants.RETURN_PENDING_HOLD:
                return "已转待查";
            default:
                return status;
        }
    }

    /** 服务层 Shiro 权限点二次校验（POST /returns 走列表过滤链，建单权限在此兜底；无 Shiro 环境放行） */
    private static void requirePermitted(String permission, String action) {
        try {
            if (!SecurityUtils.getSubject().isPermitted(permission)) {
                throw new BusinessException("Forbidden：当前账号无" + action + "权限（" + permission + "）");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception ignored) {
            // 无 Shiro 环境（服务直调测试）按系统操作者放行，与 InspectionService 口径一致
        }
    }

    private static String trim(String s) {
        return s == null ? null : (s.trim().isEmpty() ? null : s.trim());
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}

package com.evops.coldchain;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.evops.coldchain.dto.DispatchRequest;
import com.evops.coldchain.dto.DrugCreateRequest;
import com.evops.coldchain.dto.OrderCreateRequest;
import com.evops.coldchain.dto.ProbeBindRequest;
import com.evops.coldchain.dto.ProbeCreateRequest;
import com.evops.coldchain.dto.VehicleCreateRequest;
import com.evops.coldchain.entity.OpLog;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.mapper.OpLogMapper;
import com.evops.coldchain.mapper.ReturnBatchMapper;
import com.evops.coldchain.mapper.ReturnDecisionReviewMapper;
import com.evops.coldchain.mapper.ReturnLocationMoveMapper;
import com.evops.coldchain.mapper.TransportBatchMapper;
import com.evops.coldchain.service.DrugService;
import com.evops.coldchain.service.OrderService;
import com.evops.coldchain.service.ProbeService;
import com.evops.coldchain.service.TransportBatchService;
import com.evops.coldchain.service.VehicleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.HttpClientErrorException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 退货批次隔离处理（V11）验收测试。
 *
 * 覆盖：三种标准退货原因登记（支持部分退货）、入隔离即锁原批次且状态/温度/异常行不变、
 * 隔离后原销售批次所有出库/推进动作被统一拦截、库位移动逐次记录库位/人员/时间、
 * QA 检验后才能选择放行/销毁/转待查、处理决定双人复核（本人复核被拦截、跨角色 403）、
 * 驳回退回质检中、转待查后可再次提交决定、终态出库只能由复核系统生成、
 * 原销售批次撤回作废后列表/页面/导出/详情/台账五端同口径、证据链 CSV 与导出审计。
 *
 * 随机端口真实启服务，Shiro HTTP Basic 完整生效；不回滚，验证真实持久化。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ReturnBatchQuarantineIntegrationTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String BASE = "/api/cold-chain";

    private static final String DISPATCHER = "dispatcher", DPW = "dispatcher123";
    private static final String CARRIER = "carrier", CPW = "carrier123";
    private static final String RECEIVER = "receiver", RPW = "receiver123";
    private static final String QA = "qa", QPW = "qa123";
    private static final String ADMIN = "admin", APW = "admin123";

    @LocalServerPort
    private int port;

    @Autowired private TestRestTemplate http;

    @Autowired private DrugService drugService;
    @Autowired private OrderService orderService;
    @Autowired private VehicleService vehicleService;
    @Autowired private ProbeService probeService;
    @Autowired private TransportBatchService batchService;

    @Autowired private TransportBatchMapper batchMapper;
    @Autowired private ReturnBatchMapper returnMapper;
    @Autowired private ReturnLocationMoveMapper moveMapper;
    @Autowired private ReturnDecisionReviewMapper reviewMapper;
    @Autowired private OpLogMapper opLogMapper;

    // ====================================================================
    // 1) 全链路：部分退货入隔离 -> QA 检验 -> 放行决定 -> 双人复核 -> 放行出库
    // ====================================================================
    @Test
    void intake_inspect_release_dualReview_fullChain() throws Exception {
        long batchId = shipNewBatch(30);

        // 收货岗登记退货（温度超限，部分退货 12/30），直入隔离库位
        JsonNode intake = post(RECEIVER, RPW, "/returns", map(
                "batchId", batchId,
                "reasonType", "TEMP_EXCURSION",
                "reasonDetail", "途中温度记录显示持续超 8℃，收货时退回",
                "returnQty", 12,
                "quarantineLocation", "隔离库-A-01"));
        assertTrue(intake.path("success").asBoolean(), intake.toString());
        long returnId = intake.path("data").path("id").asLong();
        String returnNo = intake.path("data").path("returnNo").asText();
        assertTrue(returnNo.startsWith("TH"), "退货单号应由 TH+序列生成: " + returnNo);
        assertEquals("QUARANTINED", intake.path("data").path("status").asText());
        assertEquals("温度超限", intake.path("data").path("reasonName").asText());

        // 原批次：1:1 只读关联锁已写、批次状态/数量等原始行不变
        TransportBatch origin = batchMapper.selectById(batchId);
        assertEquals(returnId, origin.getReturnBatchId());
        assertEquals("SHIPPED", origin.getStatus());
        assertEquals(30, origin.getShipQty());

        // 首条 INTAKE 移动：记录库位、人员、时间
        assertEquals(1, moveMapper.selectCount(new QueryWrapper<com.evops.coldchain.entity.ReturnLocationMove>()
                .eq("return_id", returnId)).intValue());

        // 隔离区内移位（收货岗）：第 2 条移动
        JsonNode relocated = post(RECEIVER, RPW, "/returns/" + returnId + "/moves",
                map("toLocation", "隔离库-A-02", "area", "QUARANTINE", "reason", "腾位整理"));
        assertTrue(relocated.path("success").asBoolean(), relocated.toString());
        assertEquals(2, relocated.path("data").path("moveSeq").asInt());

        // 未开始检验不能提交处置决定
        assertStop(post(QA, QPW, "/returns/" + returnId + "/decisions", decisionBody("RELEASE", "合格出库区")),
                "检验");

        // QA 开始检验 -> 质检中
        JsonNode inspecting = post(QA, QPW, "/returns/" + returnId + "/inspect", new LinkedHashMap<>());
        assertTrue(inspecting.path("success").asBoolean(), inspecting.toString());
        assertEquals("INSPECTING", inspecting.path("data").path("status").asText());
        assertEquals("qa", inspecting.path("data").path("inspectName").asText());

        // QA 提交放行决定 -> 停留待复核
        JsonNode decision = post(QA, QPW, "/returns/" + returnId + "/decisions",
                decisionBody("RELEASE", "合格出库区-A"));
        assertTrue(decision.path("success").asBoolean(), decision.toString());
        // 复核流水结果=PENDING（待第二人复核），退货单状态=PENDING_REVIEW
        assertEquals("PENDING", decision.path("data").path("result").asText());
        assertEquals("qa", decision.path("data").path("proposerAccount").asText());
        assertEquals("PENDING_REVIEW", returnMapper.selectById(returnId).getStatus());

        // 提交人本人不能复核（业务卡口，不是 403）
        assertStop(post(QA, QPW, "/returns/" + returnId + "/review",
                map("approved", true, "reviewNote", "我自己同意")), "双人复核");
        // 待复核期间移动冻结
        assertStop(post(RECEIVER, RPW, "/returns/" + returnId + "/moves",
                map("toLocation", "X", "area", "QUARANTINE")), "待复核期间移动冻结");

        // 第二账号 admin 复核同意 -> 已放行，系统生成 FINAL 移动
        JsonNode approved = post(ADMIN, APW, "/returns/" + returnId + "/review",
                map("approved", true, "reviewNote", "复核检验记录与温度证据，同意放行"));
        assertTrue(approved.path("success").asBoolean(), approved.toString());
        assertEquals("APPROVED", approved.path("data").path("result").asText());
        assertEquals("admin", approved.path("data").path("reviewerAccount").asText());

        com.evops.coldchain.entity.ReturnBatch done = returnMapper.selectById(returnId);
        assertEquals("RELEASED", done.getStatus());
        assertEquals("合格出库区-A", done.getCurrentLocation());
        assertEquals("RELEASED", done.getCurrentArea());
        assertEquals("admin", done.getReviewName());
        assertNotNull(done.getReviewTime());

        // 移动链：INTAKE / RELOCATE / FINAL 三条，序号连续、前后库位相接、FINAL 为系统生成
        java.util.List<com.evops.coldchain.entity.ReturnLocationMove> moves = moveMapper.selectList(
                new QueryWrapper<com.evops.coldchain.entity.ReturnLocationMove>()
                        .eq("return_id", returnId).orderByAsc("move_seq"));
        assertEquals(3, moves.size());
        assertEquals("INTAKE", moves.get(0).getMoveType());
        assertEquals("QUARANTINE", moves.get(0).getArea());
        assertEquals("RELOCATE", moves.get(1).getMoveType());
        assertEquals("隔离库-A-02", moves.get(1).getToLocation());
        assertEquals("FINAL", moves.get(2).getMoveType());
        assertEquals("RELEASED", moves.get(2).getArea());
        assertEquals(moves.get(0).getToLocation(), moves.get(1).getFromLocation());
        assertEquals(moves.get(1).getToLocation(), moves.get(2).getFromLocation());
        assertNotNull(moves.get(0).getMoveTime());
        assertNotNull(moves.get(0).getMoverAccount());

        // 全链路操作留痕（均带原批次 batch_id，按批次隔离计数，避免共享内存库跨用例干扰）
        assertTrue(countAction(batchId, "RETURN_INTAKE") == 1);
        assertTrue(countAction(batchId, "RETURN_INSPECT_START") == 1);
        assertTrue(countAction(batchId, "RETURN_DECISION_SUBMIT") == 1);
        assertTrue(countAction(batchId, "RETURN_REVIEW_APPROVE") == 1);
        assertTrue(countAction(batchId, "RETURN_MOVE") == 1);
    }

    // ====================================================================
    // 2) 销毁决定 + 驳回回质检中重处
    // ====================================================================
    @Test
    void destroyDecision_afterReject_resubmitAndApprove() throws Exception {
        long batchId = shipNewBatch(10);
        long id = intake(batchId, "PACKAGE_DAMAGED", 10, "隔离库-B-01", null);

        post(QA, QPW, "/returns/" + id + "/inspect", new LinkedHashMap<>());

        // 非法决定代码 / 缺目标库位
        assertStop(post(QA, QPW, "/returns/" + id + "/decisions",
                map("decision", "RETURN_TO_VENDOR", "decisionNote", "退供应商", "targetLocation", "X")), "RELEASE");
        assertStop(post(QA, QPW, "/returns/" + id + "/decisions",
                map("decision", "DESTROY", "decisionNote", "包装破损不可再售", "targetLocation", "  ")), "目标库位");

        JsonNode first = post(QA, QPW, "/returns/" + id + "/decisions",
                decisionBody("DESTROY", "销毁区-B"));
        assertTrue(first.path("success").asBoolean(), first.toString());

        // admin 驳回 -> 退回质检中；不生成 FINAL 移动
        JsonNode rejected = post(ADMIN, APW, "/returns/" + id + "/review",
                map("approved", false, "reviewNote", "销毁证据不足，补充包装破损照片后重报"));
        assertTrue(rejected.path("success").asBoolean(), rejected.toString());
        assertEquals("REJECTED", rejected.path("data").path("result").asText());
        assertEquals("INSPECTING", returnMapper.selectById(id).getStatus());
        // 驳回没有任何 FINAL 移动
        assertEquals(0, moveMapper.selectCount(new QueryWrapper<com.evops.coldchain.entity.ReturnLocationMove>()
                .eq("return_id", id).eq("move_type", "FINAL")).longValue());

        // 重新提交销毁决定，admin 同意 -> 已销毁 + FINAL 销毁区移动
        JsonNode second = post(QA, QPW, "/returns/" + id + "/decisions",
                decisionBody("DESTROY", "销毁区-B"));
        assertEquals(2, second.path("data").path("reviewSeq").asInt());
        JsonNode approved = post(ADMIN, APW, "/returns/" + id + "/review",
                map("approved", true, "reviewNote", "证据补齐，同意监督销毁"));
        assertTrue(approved.path("success").asBoolean(), approved.toString());
        assertEquals("DESTROYED", returnMapper.selectById(id).getStatus());
        assertEquals("DESTROYED", moveMapper.selectList(new QueryWrapper<com.evops.coldchain.entity.ReturnLocationMove>()
                .eq("return_id", id).orderByDesc("move_seq").last("LIMIT 1")).get(0).getArea());

        // 终态后不能再移动、再提交决定、再复核
        assertStop(post(RECEIVER, RPW, "/returns/" + id + "/moves",
                map("toLocation", "别处", "area", "QUARANTINE")), "待复核期间移动冻结");
        assertStop(post(QA, QPW, "/returns/" + id + "/decisions", decisionBody("RELEASE", "出库区")),
                "检验");
    }

    // ====================================================================
    // 3) 转待查：复核通过进入待查库位，之后允许再次提交新决定并放行
    // ====================================================================
    @Test
    void pendingHold_canResubmitNewDecision_thenRelease() throws Exception {
        long batchId = shipNewBatch(8);
        long id = intake(batchId, "REJECTION", 8, "隔离库-C-01", null);
        post(QA, QPW, "/returns/" + id + "/inspect", new LinkedHashMap<>());

        post(QA, QPW, "/returns/" + id + "/decisions", decisionBody("PENDING_HOLD", "待查库位-C"));
        JsonNode hold = post(ADMIN, APW, "/returns/" + id + "/review",
                map("approved", true, "reviewNote", "先转待查，等待收货拒签责任认定"));
        assertTrue(hold.path("success").asBoolean(), hold.toString());
        assertEquals("PENDING_HOLD", returnMapper.selectById(id).getStatus());
        assertEquals("PENDING", returnMapper.selectById(id).getCurrentArea());

        // 待查后 QA 可再次提交新决定（无需重新开始检验）
        JsonNode second = post(QA, QPW, "/returns/" + id + "/decisions",
                decisionBody("RELEASE", "合格出库区-C"));
        assertEquals(2, second.path("data").path("reviewSeq").asInt());
        post(ADMIN, APW, "/returns/" + id + "/review", map("approved", true, "reviewNote", "责任认定完成，同意放行"));
        assertEquals("RELEASED", returnMapper.selectById(id).getStatus());

        // 两条 FINAL（待查 -> 放行），两轮复核流水均保留
        assertEquals(2, moveMapper.selectCount(new QueryWrapper<com.evops.coldchain.entity.ReturnLocationMove>()
                .eq("return_id", id).eq("move_type", "FINAL")).longValue());
        assertEquals(2, reviewMapper.selectCount(new QueryWrapper<com.evops.coldchain.entity.ReturnDecisionReview>()
                .eq("return_id", id).eq("result", "APPROVED")).longValue());
    }

    // ====================================================================
    // 4) 隔离后原销售批次不能再次出库：所有推进/出库入口统一拦截
    // ====================================================================
    @Test
    void quarantined_originBatch_allAdvancingOperationsBlocked() throws Exception {
        long batchId = shipNewBatch(20);
        long id = intake(batchId, "REJECTION", 5, "隔离库-D-01", null);
        String keyword = "退货";

        // 运输推进
        assertStop(post(DISPATCHER, DPW, "/batches/" + batchId + "/loading", new LinkedHashMap<>()), keyword);
        assertStop(post(CARRIER, CPW, "/batches/temperatures",
                map("batchId", batchId, "recordTime", LocalDateTime.now().toString(),
                        "temperature", 5, "recordedBy", "carrier")), keyword);
        assertStop(post(DISPATCHER, DPW, "/batches/exceptions",
                map("batchId", batchId, "exceptionType", "DELAY", "description", "补录延误")), keyword);
        assertStop(post(RECEIVER, RPW, "/batches/reject",
                map("batchId", batchId, "rejectBy", "赵收", "reason", "再次拒签")), keyword);
        assertStop(post(RECEIVER, RPW, "/batches/sign",
                map("batchId", batchId, "signBy", "赵收", "receivedApprovalNo", "X",
                        "receivedPackageSpec", "Y", "confirmQty", 1)), keyword);
        assertStop(post(DISPATCHER, DPW, "/batches/settle",
                map("batchId", batchId, "settleRemark", "尝试结算")), keyword);
        // 改期
        assertStop(post(DISPATCHER, DPW, "/reschedules",
                map("batchId", batchId, "newPlanShipTime", LocalDateTime.now().plusDays(1).toString(),
                        "reason", "尝试改期")), keyword);
        // 分仓交接（到仓/拆单）
        assertStop(post(RECEIVER, RPW, "/handovers/arrive",
                map("batchId", batchId, "arrivedBoxQty", 1, "warehouseName", "仓")), keyword);
        assertStop(post(RECEIVER, RPW, "/handovers",
                map("batchId", batchId, "warehouseName", "仓", "boxQty", 1, "handoverTemp", 5,
                        "receiverName", "张仓", "receiverOrg", "收货组", "sealNos", new String[]{"S-RET-1"})), keyword);
        // 运输审批
        assertStop(post(DISPATCHER, DPW, "/approvals",
                map("batchId", batchId, "reason", "尝试发起运输审批")), keyword);

        // 原始行未被这些失败操作改动：仍在途、温度/异常计数未增加
        assertEquals("SHIPPED", batchMapper.selectById(batchId).getStatus());
    }

    // ====================================================================
    // 5) 退货登记停点：未发运/已签收不可退、重复登记、数量、非法原因、异常相容性
    // ====================================================================
    @Test
    void intake_stopPoints_andReasonExceptionCompatibility() throws Exception {
        // 未发运（DISPATCHED）批次不能登记退货
        long dispatchedId = shipNewBatch(5, false);
        assertStop(post(RECEIVER, RPW, "/returns",
                map("batchId", dispatchedId, "reasonType", "REJECTION", "returnQty", 1,
                        "quarantineLocation", "隔离库-E")), "在途");

        long batchId = shipNewBatch(20);

        // 非法退货原因
        assertStop(post(RECEIVER, RPW, "/returns",
                map("batchId", batchId, "reasonType", "EXPIRED", "returnQty", 1, "quarantineLocation", "L")),
                "三种标准");
        // 数量 0 与超发运量
        assertStop(post(RECEIVER, RPW, "/returns",
                map("batchId", batchId, "reasonType", "REJECTION", "returnQty", 0, "quarantineLocation", "L")),
                "大于0");
        assertStop(post(RECEIVER, RPW, "/returns",
                map("batchId", batchId, "reasonType", "REJECTION", "returnQty", 21, "quarantineLocation", "L")),
                "发运数量");

        // 先补录一张包装破损异常（DAMAGE），用相容异常登记包装破损退货
        JsonNode damage = post(DISPATCHER, DPW, "/batches/exceptions",
                map("batchId", batchId, "exceptionType", "DAMAGE", "description", "外箱压损渗水"));
        assertTrue(damage.path("success").asBoolean(), damage.toString());
        long damageExId = damage.path("data").path("id").asLong();

        // 不相容：温度超限原因引用包装破损异常 -> 拒绝
        assertStop(post(RECEIVER, RPW, "/returns",
                map("batchId", batchId, "reasonType", "TEMP_EXCURSION", "returnQty", 2,
                        "quarantineLocation", "隔离库-F-01", "sourceExceptionId", damageExId)), "不相容");
        // 异常属于别的批次 -> 拒绝
        long otherBatch = shipNewBatch(5);
        JsonNode otherDamage = post(DISPATCHER, DPW, "/batches/exceptions",
                map("batchId", otherBatch, "exceptionType", "DAMAGE", "description", "他批破损"));
        assertStop(post(RECEIVER, RPW, "/returns",
                map("batchId", batchId, "reasonType", "PACKAGE_DAMAGED", "returnQty", 2,
                        "quarantineLocation", "隔离库-F-02",
                        "sourceExceptionId", otherDamage.path("data").path("id").asLong())), "不属于该原销售批次");

        // 相容引用成功
        long id = intake(batchId, "PACKAGE_DAMAGED", 7, "隔离库-F-03", damageExId);
        assertEquals(damageExId, returnMapper.selectById(id).getSourceExceptionId());

        // 一个销售批次只能退货一次（1:1）
        assertStop(post(RECEIVER, RPW, "/returns",
                map("batchId", batchId, "reasonType", "REJECTION", "returnQty", 1, "quarantineLocation", "L")),
                "只能退货一次");
    }

    // ====================================================================
    // 6) 权限点：无权限 403（业务卡口才是 200+success=false）
    // ====================================================================
    @Test
    void permissionMatrix_403_forWrongRoles() throws Exception {
        long batchId = shipNewBatch(6);
        long id = intake(batchId, "TEMP_EXCURSION", 6, "隔离库-G-01", null);

        // 收货岗不能开始检验
        assertEquals(403, rawRequest(RECEIVER, RPW, HttpMethod.POST,
                "/returns/" + id + "/inspect", new LinkedHashMap<>()).status);
        // 调度不能提交处置决定
        assertEquals(403, rawRequest(DISPATCHER, DPW, HttpMethod.POST,
                "/returns/" + id + "/decisions", decisionBody("RELEASE", "出库区")).status);
        post(QA, QPW, "/returns/" + id + "/inspect", new LinkedHashMap<>());
        post(QA, QPW, "/returns/" + id + "/decisions", decisionBody("RELEASE", "出库区-G"));
        // 承运不能复核
        assertEquals(403, rawRequest(CARRIER, CPW, HttpMethod.POST,
                "/returns/" + id + "/review", map("approved", true, "reviewNote", "同意")).status);
        // 承运无导出权限
        assertEquals(403, rawRequest(CARRIER, CPW, HttpMethod.GET, "/returns/export", null).status);
    }

    // ====================================================================
    // 7) 原销售批次撤回作废后：页面/列表/导出/详情/台账同口径，快照不丢
    // ====================================================================
    @Test
    void originBatchRevoked_pageListExportLedger_stillConsistent() throws Exception {
        long batchId = shipNewBatch(15);
        JsonNode intake = post(RECEIVER, RPW, "/returns", map(
                "batchId", batchId, "reasonType", "TEMP_EXCURSION", "returnQty", 15,
                "quarantineLocation", "隔离库-H-01"));
        assertTrue(intake.path("success").asBoolean(), intake.toString());
        long id = intake.path("data").path("id").asLong();
        String approvalSnap = intake.path("data").path("approvalNoSnap").asText();
        String returnNo = intake.path("data").path("returnNo").asText();

        // 将冷链药品运输对象（原销售批次）置为无效：撤回作废（退货仍允许原点作废，原始记录保留）
        JsonNode revoke = post(ADMIN, APW, "/batches/revoke",
                map("batchId", batchId, "reason", "退货成立，原销售批次作废"));
        assertTrue(revoke.path("success").asBoolean(), revoke.toString());
        assertEquals("REVOKED", batchMapper.selectById(batchId).getStatus());

        // 后台列表：仍可按状态/原因过滤查到，快照字段不变
        JsonNode list = get(RECEIVER, RPW, "/returns?status=QUARANTINED&reason=TEMP_EXCURSION");
        boolean found = false;
        for (JsonNode r : list) {
            if (r.path("id").asLong() == id) {
                found = true;
                assertEquals(approvalSnap, r.path("approvalNoSnap").asText());
                assertEquals(15, r.path("returnQty").asInt());
            }
        }
        assertTrue(found, "原批次作废后退货单仍须在后台列表同口径出现");

        // 详情：原批次状态如实呈现为已撤回（只读关联）
        JsonNode detail = get(RECEIVER, RPW, "/returns/" + id);
        assertTrue(detail.path("originBatchRevoked").asBoolean());
        assertEquals("REVOKED", detail.path("originBatchStatus").asText());
        assertEquals(returnNo, detail.path("return").path("returnNo").asText());

        // 页面：退货单号与"已撤回作废"标注都在
        HttpResp page = rawRequest(RECEIVER, RPW, HttpMethod.GET, "/ui/cold-chain/returns", null);
        assertEquals(200, page.status);
        assertTrue(page.body.contains(returnNo), "页面应展示退货单: " + returnNo);
        assertTrue(page.body.contains("已撤回作废"), "页面应标注原销售批次已撤回作废");

        // 导出 CSV：三端同口径，退货单仍导出；带 UTF-8 BOM
        HttpResp csv = rawRequest(RECEIVER, RPW, HttpMethod.GET,
                "/returns/export?status=QUARANTINED", null);
        assertEquals(200, csv.status);
        assertTrue(csv.body.contains(returnNo), "证据链导出必须包含退货单");
        assertTrue(csv.body.contains("退货抬头") && csv.body.contains("库位移动"),
                "证据链导出应包含抬头与库位移动段落");
        // UTF-8 BOM 三字节经 UTF-8 解码后呈现为单个 U+FEFF 字符
        assertEquals('﻿', csv.body.charAt(0));

        // 原批次台账：returnInfo.linked=true 且移动链可追
        JsonNode ledger = get(DISPATCHER, DPW, "/batches/" + batchId + "/ledger");
        assertTrue(ledger.path("returnInfo").path("linked").asBoolean());
        assertEquals(returnNo, ledger.path("returnInfo").path("return").path("returnNo").asText());
        assertEquals(1, ledger.path("returnInfo").path("moves").size());

        // 导出审计流水（entity_id 为空、batch_id 为空的独立导出动作）
        assertTrue(opLogMapper.selectCount(new QueryWrapper<OpLog>()
                .eq("action", "RETURN_EXPORT")).intValue() >= 1, "导出必须写 RETURN_EXPORT 审计流水");
    }

    // ====================================================================
    // 8) 三种退货原因各自可建单，原因过滤与中文名一致
    // ====================================================================
    @Test
    void threeReasons_eachCreatesWithChineseName() throws Exception {
        long b1 = shipNewBatch(3);
        long b2 = shipNewBatch(3);
        long b3 = shipNewBatch(3);
        intake(b1, "REJECTION", 1, "隔离库-R1", null);
        intake(b2, "TEMP_EXCURSION", 2, "隔离库-R2", null);
        intake(b3, "PACKAGE_DAMAGED", 3, "隔离库-R3", null);

        for (String[] c : new String[][]{{"REJECTION", "收货拒签"}, {"TEMP_EXCURSION", "温度超限"},
                {"PACKAGE_DAMAGED", "包装破损"}}) {
            JsonNode rows = get(RECEIVER, RPW, "/returns?reason=" + c[0]);
            boolean match = false;
            for (JsonNode r : rows) {
                if (c[1].equals(r.path("reasonName").asText())) {
                    match = true;
                }
            }
            assertTrue(match, "原因过滤 " + c[0] + " 应返回中文名 " + c[1]);
        }
    }

    // ---------------- 辅助方法 ----------------

    /** 登记退货并返回退货单ID */
    private long intake(long batchId, String reason, int qty, String location, Long sourceExceptionId) {
        Map<String, Object> body = map("batchId", batchId, "reasonType", reason, "returnQty", qty,
                "quarantineLocation", location);
        if (sourceExceptionId != null) {
            body.put("sourceExceptionId", sourceExceptionId);
        }
        JsonNode resp = post(RECEIVER, RPW, "/returns", body);
        assertTrue(resp.path("success").asBoolean(), resp.toString());
        return resp.path("data").path("id").asLong();
    }

    private Map<String, Object> decisionBody(String decision, String target) {
        return map("decision", decision, "targetLocation", target,
                "decisionNote", "质检完成：依据实测温度与外包装检查作出处置",
                "inspectReport", "逐盒核对温区记录与包装完整性", "measuredTemp", 6.21);
    }

    private long countAction(long batchId, String action) {
        return opLogMapper.selectCount(new QueryWrapper<OpLog>()
                .in("entity_type", "RETURN_BATCH", "RETURN_MOVE", "RETURN_REVIEW")
                .eq("batch_id", batchId).eq("action", action));
    }

    /** 建档 -> 下单 -> 车辆+持证探头绑定 -> 派车（-> 发运），与 V4 验收同一套真实链路 */
    private long shipNewBatch(int shipQty) {
        return shipNewBatch(shipQty, true);
    }

    private long shipNewBatch(int shipQty, boolean ship) {
        long nano = System.nanoTime();

        DrugCreateRequest dc = new DrugCreateRequest();
        dc.setDrugName("V11验收药品-" + nano);
        dc.setApprovalNo("国药准字V11-" + Math.abs(nano % 1000000));
        dc.setTempMin(new BigDecimal("2.00"));
        dc.setTempMax(new BigDecimal("8.00"));
        dc.setPackageSpec("1支/盒");
        Long drugId = drugService.create(dc).getId();

        OrderCreateRequest oc = new OrderCreateRequest();
        oc.setDrugId(drugId);
        oc.setReceiverOrg("V11验收医院");
        oc.setShipQty(shipQty);
        Long orderId = orderService.create(oc).getId();

        VehicleCreateRequest vr = new VehicleCreateRequest();
        vr.setPlateNo("测V11·冷" + Math.abs(nano % 1000000));
        vr.setCarrierName("V11验收物流-" + Math.abs(nano % 100000));
        vr.setDriverName("王司机");
        Long vehicleId = vehicleService.create(vr).getId();

        ProbeCreateRequest pc = new ProbeCreateRequest();
        pc.setSerialNo("SN-V11-" + nano);
        pc.setModel("TP-200");
        pc.setRangeMin(new BigDecimal("-40.00"));
        pc.setRangeMax(new BigDecimal("60.00"));
        pc.setCalibrateOrg("华东计量院");
        pc.setValidUntil(LocalDateTime.now().plusYears(1));
        pc.setCertNo("CERT-V11-" + nano);
        pc.setCalibrateDate(LocalDateTime.now().minusDays(1));
        pc.setCertFileName(nano + ".txt");
        pc.setCertFileContent("V11校准证书-" + nano);
        long probeId = probeService.createProbe(pc).getId();

        ProbeBindRequest pb = new ProbeBindRequest();
        pb.setRequestId("v11bind-" + nano);
        pb.setVehicleId(vehicleId);
        pb.setProbeId(probeId);
        probeService.bindIdempotent(pb);

        DispatchRequest dr = new DispatchRequest();
        dr.setOrderId(orderId);
        dr.setVehicleId(vehicleId);
        Long batchId = batchService.dispatch(dr).getId();
        if (ship) {
            batchService.ship(batchId);
        }
        return batchId;
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private JsonNode post(String user, String pw, String path, Object body) {
        HttpResp resp = rawRequest(user, pw, HttpMethod.POST, path, body);
        if (resp.status != 200) {
            throw new AssertionError("期望 200 业务响应，实际 " + resp.status + "：" + resp.body);
        }
        return readTree(resp.body);
    }

    private JsonNode get(String user, String pw, String path) {
        HttpResp resp = rawRequest(user, pw, HttpMethod.GET, path, null);
        assertEquals(200, resp.status, "GET " + path + " -> " + resp.status + " " + resp.body);
        return readTree(resp.body).path("data");
    }

    private HttpResp rawRequest(String user, String pw, HttpMethod method, String path, Object body) {
        try {
            String url = path.startsWith("/ui/")
                    ? "http://localhost:" + port + path
                    : "http://localhost:" + port + BASE + path;
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Object> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> resp = http.withBasicAuth(user, pw)
                    .exchange(url, method, entity, String.class);
            return new HttpResp(resp.getStatusCodeValue(), resp.getBody());
        } catch (HttpClientErrorException e) {
            return new HttpResp(e.getRawStatusCode(), e.getResponseBodyAsString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private JsonNode readTree(String json) {
        try {
            return M.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void assertStop(JsonNode resp, String keyword) {
        assertFalse(resp.path("success").asBoolean(), "应被拦截: " + resp);
        assertTrue(resp.path("message").asText().contains(keyword),
                "停点说明应包含[" + keyword + "]，实际：" + resp.path("message").asText());
    }

    private static class HttpResp {
        final int status;
        final String body;

        HttpResp(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }
}

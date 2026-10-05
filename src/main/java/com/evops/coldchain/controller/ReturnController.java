package com.evops.coldchain.controller;

import com.evops.coldchain.CcConstants;
import com.evops.coldchain.dto.ReturnDecisionRequest;
import com.evops.coldchain.dto.ReturnIntakeRequest;
import com.evops.coldchain.dto.ReturnMoveRequest;
import com.evops.coldchain.dto.ReturnReviewRequest;
import com.evops.coldchain.entity.ReturnBatch;
import com.evops.coldchain.entity.ReturnDecisionReview;
import com.evops.coldchain.entity.ReturnLocationMove;
import com.evops.coldchain.service.OpLogService;
import com.evops.coldchain.service.ReturnBatchService;
import com.evops.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 退货批次隔离处理（V11）：
 * 退货登记（收货拒签/温度超限/包装破损）直入隔离库位 -> QA 检验 -> 提交放行/销毁/转待查决定
 * -> 第二账号双人复核生效；每次库位移动记录库位/人员/时间，原销售批次保持只读关联。
 * 列表、详情与证据链 CSV 导出与管理页面共用 ReturnBatchService 同一查询口径。
 */
@RestController
@RequestMapping("/api/cold-chain/returns")
public class ReturnController {

    private final ReturnBatchService returnService;
    private final OpLogService opLogService;

    public ReturnController(ReturnBatchService returnService, OpLogService opLogService) {
        this.returnService = returnService;
        this.opLogService = opLogService;
    }

    /** 退货登记：在途销售批次按标准原因退货并直入隔离库位（同时锁定原批次） */
    @PostMapping
    public ApiResponse<ReturnBatch> intake(@Valid @RequestBody ReturnIntakeRequest req) {
        return ApiResponse.ok(returnService.intake(req));
    }

    /** QA 开始质量检验（隔离中 -> 质检中） */
    @PostMapping("/{id}/inspect")
    public ApiResponse<ReturnBatch> inspect(@PathVariable("id") Long id) {
        return ApiResponse.ok(returnService.startInspection(id));
    }

    /** QA 提交处置决定（放行/销毁/转待查），提交后停留待双人复核 */
    @PostMapping("/{id}/decisions")
    public ApiResponse<ReturnDecisionReview> decision(@PathVariable("id") Long id,
                                                      @Valid @RequestBody ReturnDecisionRequest req) {
        return ApiResponse.ok(returnService.submitDecision(id, req));
    }

    /** 第二账号双人复核：true 同意生效（系统生成终态移动）/ false 驳回退回质检中 */
    @PostMapping("/{id}/review")
    public ApiResponse<ReturnDecisionReview> review(@PathVariable("id") Long id,
                                                    @Valid @RequestBody ReturnReviewRequest req) {
        return ApiResponse.ok(returnService.review(id, req.getApproved(), req.getReviewNote()));
    }

    /** 处理过程中的隔离/质检区内库位移动（终态出库只能由复核通过后系统生成） */
    @PostMapping("/{id}/moves")
    public ApiResponse<ReturnLocationMove> move(@PathVariable("id") Long id,
                                                @Valid @RequestBody ReturnMoveRequest req) {
        return ApiResponse.ok(returnService.relocate(id, req));
    }

    /** 退货单列表（与页面、导出同一查询口径；可按状态/退货原因过滤） */
    @GetMapping
    public ApiResponse<List<ReturnBatch>> list(@RequestParam(value = "status", required = false) String status,
                                               @RequestParam(value = "reason", required = false) String reason) {
        return ApiResponse.ok(returnService.searchReturns(status, reason));
    }

    /** 待双人复核队列 */
    @GetMapping("/pending-review")
    public ApiResponse<List<ReturnBatch>> pendingReview() {
        return ApiResponse.ok(returnService.listPendingReview());
    }

    /** 退货单详情（抬头 + 只读原批次 + 库位移动链 + 双人复核链） */
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable("id") Long id) {
        return ApiResponse.ok(returnService.detail(id));
    }

    /** 按原销售批次反查退货单（台账/页面用） */
    @GetMapping("/batch/{batchId}")
    public ApiResponse<ReturnBatch> byBatch(@PathVariable("batchId") Long batchId) {
        return ApiResponse.ok(returnService.findByBatch(batchId));
    }

    /**
     * 证据链 CSV 导出：与列表/页面同一行集同一口径（原批次事后作废仍完整导出）；
     * 逐次写一条导出审计流水，只读导出不改写任何退货数据。
     */
    @GetMapping("/export")
    public void export(@RequestParam(value = "status", required = false) String status,
                       @RequestParam(value = "reason", required = false) String reason,
                       HttpServletResponse response) throws IOException {
        List<String[]> rows = returnService.evidenceRows(status, reason);

        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"return_quarantine_evidence.csv\"");
        // UTF-8 BOM，便于 Excel 正确识别中文
        response.getOutputStream().write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        PrintWriter writer = new PrintWriter(new java.io.OutputStreamWriter(response.getOutputStream(),
                java.nio.charset.StandardCharsets.UTF_8));
        writer.println(String.join(",", returnService.evidenceHeader()));
        for (String[] row : rows) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < row.length; i++) {
                if (i > 0) {
                    line.append(",");
                }
                line.append(csv(row[i]));
            }
            writer.println(line.toString());
        }
        writer.flush();

        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("status", status);
        filter.put("reason", reason);
        filter.put("rows", rows.size());
        opLogService.log(CcConstants.ENT_RETURN_BATCH, null, null, CcConstants.ACT_RETURN_EXPORT,
                "导出退货批次隔离证据链 CSV（页面/导出/后台同口径，原批次作废仍导出）：" + filter
                        + "，共 " + rows.size() + " 行；只读导出，不改写任何退货数据");
    }

    private String csv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}

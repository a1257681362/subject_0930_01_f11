package com.evops.coldchain.controller;

import com.evops.coldchain.CcConstants;
import com.evops.coldchain.entity.ReturnBatch;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.mapper.TransportBatchMapper;
import com.evops.coldchain.service.ReturnBatchService;
import org.apache.shiro.SecurityUtils;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 退货批次隔离处理管理页面（V11，Thymeleaf 服务端渲染 + fetch 调用既有 API）。
 * 页面列表直接调用 ReturnBatchService.searchReturns —— 与后台列表 API、证据链 CSV 导出
 * 完全同一查询口径；原销售批次事后撤回作废，页面仍按退货单快照展示并标注原点只读状态。
 */
@Controller
@RequestMapping("/ui/cold-chain")
public class ReturnPageController {

    @Resource
    private ReturnBatchService returnService;
    @Resource
    private TransportBatchMapper batchMapper;

    @GetMapping("/returns")
    public String returns(@RequestParam(value = "status", required = false) String status,
                          @RequestParam(value = "reason", required = false) String reason,
                          Model model) {
        // 与 GET /api/cold-chain/returns、GET /api/cold-chain/returns/export 共用同一查询方法
        List<ReturnBatch> returns = returnService.searchReturns(status, reason);

        List<Map<String, Object>> views = new ArrayList<>();
        for (ReturnBatch ret : returns) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("ret", ret);
            // 原销售批次保持只读关联（可能已被撤回作废，仍原样呈现其状态）
            TransportBatch origin = batchMapper.selectById(ret.getBatchId());
            view.put("originStatus", origin == null ? "原批次不存在" : origin.getStatus());
            view.put("originRevoked", origin != null
                    && CcConstants.BATCH_REVOKED.equals(origin.getStatus()));
            view.put("moves", returnService.listMoves(ret.getId()));
            view.put("reviews", returnService.listReviews(ret.getId()));
            views.add(view);
        }
        model.addAttribute("views", views);
        model.addAttribute("statusFilter", status);
        model.addAttribute("reasonFilter", reason);

        // 页面按钮按当前账号权限点渲染；具体动作再经 API 过滤链与服务层双重校验
        model.addAttribute("canCreate",
                SecurityUtils.getSubject().isPermitted(CcConstants.PERM_RETURN_CREATE));
        model.addAttribute("canInspect",
                SecurityUtils.getSubject().isPermitted(CcConstants.PERM_RETURN_INSPECT));
        model.addAttribute("canReview",
                SecurityUtils.getSubject().isPermitted(CcConstants.PERM_RETURN_REVIEW));
        model.addAttribute("canExport",
                SecurityUtils.getSubject().isPermitted(CcConstants.PERM_RETURN_EXPORT));
        return "coldchain/returns";
    }
}

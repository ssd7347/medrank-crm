package com.mbbscrm.crm.refund;

import java.util.List;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mbbscrm.crm.refund.RefundRuleDtos.RefundRuleView;

/** Read-only list of approved refund rules. Changes go through /api/change-requests (entity REFUND_RULE). */
@RestController
@RequestMapping("/api/refund-rules")
public class RefundRuleController {

    private final RefundRuleRepository rules;

    public RefundRuleController(RefundRuleRepository rules) {
        this.rules = rules;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<RefundRuleView> list(@RequestParam int year) {
        return rules.findByAcademicYearOrderByAuthorityIdAscCollegeIdAscRoundTypeAsc(year).stream()
                .map(RefundRuleView::of).toList();
    }
}

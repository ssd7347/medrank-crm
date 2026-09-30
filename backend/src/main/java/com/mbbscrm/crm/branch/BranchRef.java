package com.mbbscrm.crm.branch;

/** Minimal branch reference for labels and dropdowns. */
public record BranchRef(Long id, String name, String code) {

    public static BranchRef of(Branch b) {
        return b == null ? null : new BranchRef(b.getId(), b.getName(), b.getCode());
    }
}

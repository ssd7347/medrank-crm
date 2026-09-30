package com.mbbscrm.crm.marketing;

import com.mbbscrm.crm.common.LeadSource;

/** Minimal campaign reference for lead forms and labels. */
public record CampaignRef(Long id, String name, LeadSource channel) {

    public static CampaignRef of(Campaign c) {
        return c == null ? null : new CampaignRef(c.getId(), c.getName(), c.getChannel());
    }
}

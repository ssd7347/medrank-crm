package com.mbbscrm.crm.predictor;

import com.mbbscrm.crm.college.College;
import com.mbbscrm.crm.common.CounsellingRound;
import com.mbbscrm.crm.common.Quota;

/** One closing rank joined to its college, as read by the predictor. */
public record CutoffRow(College college, Quota quota, int year, CounsellingRound round, int closingRank) {
}

package com.aicallcrm.crm.voice;

import java.time.LocalDate;

import org.springframework.stereotype.Component;

import com.aicallcrm.crm.common.ApiException;
import com.aicallcrm.crm.contact.ImportService;
import com.aicallcrm.crm.contact.ImportService.CallsStarted;
import com.aicallcrm.crm.settings.Features;
import com.aicallcrm.crm.settings.Features.Feature;
import com.aicallcrm.crm.voice.CampaignService.CampaignDetail;
import com.aicallcrm.crm.voice.CampaignService.CampaignRequest;
import com.aicallcrm.crm.voice.CampaignService.Preview;

/**
 * "Call everyone in this file now": after an upload, a campaign for exactly that upload is created and started.
 * It is an ordinary campaign, so every rule still applies on each call (consent, calling hours, do-not-call,
 * do-not-disturb, retries, the kill switch) and it can be paused from the campaign screen like any other.
 */
@Component
class ImportCalls implements ImportService.CallStarter {

    private final CampaignService campaigns;
    private final VoiceScriptService scripts;
    private final Features features;

    ImportCalls(CampaignService campaigns, VoiceScriptService scripts, Features features) {
        this.campaigns = campaigns;
        this.scripts = scripts;
        this.features = features;
    }

    @Override
    public void check(String scriptName) {
        if (!features.isOn(Feature.AI_CALLS)) {
            throw ApiException.forbidden("AI calls are not part of your plan");
        }
        if (!scripts.outboundNames().contains(scriptName)) {
            throw ApiException.badRequest("There is no outgoing-call script named \"" + scriptName + "\"");
        }
        if (scripts.approved(scriptName).isEmpty()) {
            throw ApiException.badRequest("Approve the script \"" + scriptName + "\" before calling anyone with it");
        }
    }

    @Override
    public CallsStarted start(Long batchId, String fileName, String scriptName) {
        String name = cut(fileName.isBlank() ? "Upload " + batchId : fileName, 90) + " (" + LocalDate.now(ContactFacts.IST) + ")";
        CampaignDetail d = campaigns.create(new CampaignRequest(name, scriptName, null, batchId, null, null, null,
                null, null, null, null, null, null, null));
        Long id = d.campaign().id();
        if (d.campaign().counts().pending() == 0) {
            // Everyone in the file is marked "Do not call": nothing to start.
            return new CallsStarted(id, name, 0, 0, 0, 0);
        }
        Preview p = campaigns.start(id).preview();
        int waiting = p == null ? 0 : p.waiting().values().stream().mapToInt(Integer::intValue).sum();
        int notAllowed = p == null ? 0 : p.skipped().values().stream().mapToInt(Integer::intValue).sum();
        return new CallsStarted(id, name, d.campaign().counts().total(), p == null ? 0 : p.eligibleNow(), waiting,
                notAllowed);
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}

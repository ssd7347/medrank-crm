package com.aicallcrm.crm.voice;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.aicallcrm.crm.voice.Voice.CallStatus;
import com.aicallcrm.crm.voice.Voice.CallbackStatus;
import com.aicallcrm.crm.voice.Voice.CampaignStatus;
import com.aicallcrm.crm.voice.Voice.Outcome;
import com.aicallcrm.crm.voice.Voice.TargetStatus;

// Repositories of the voice agent, kept together because each is only a few lines.

interface ContactConsentRepository extends JpaRepository<ContactConsent, Long> {

    List<ContactConsent> findByPhoneOrderByCapturedAtDescIdDesc(String phone);

    long countByRevokedAtGreaterThanEqual(Instant since);
}

interface DndCheckRepository extends JpaRepository<DndCheck, Long> {

    Optional<DndCheck> findFirstByPhoneHashOrderByCheckedAtDescIdDesc(String phoneHash);
}

interface VoiceScriptRepository extends JpaRepository<VoiceScript, Long> {

    List<VoiceScript> findAllByOrderByNameAscVersionDesc();

    List<VoiceScript> findByNameOrderByVersionDesc(String name);

    Optional<VoiceScript> findFirstByNameAndActiveTrueOrderByVersionDesc(String name);

    Optional<VoiceScript> findFirstByKindAndActiveTrueOrderByIdAsc(Voice.ScriptKind kind);
}

interface VoiceCampaignRepository extends JpaRepository<VoiceCampaign, Long> {

    List<VoiceCampaign> findAllByOrderByCreatedAtDesc();

    List<VoiceCampaign> findByStatus(CampaignStatus status);
}

interface VoiceCampaignTargetRepository extends JpaRepository<VoiceCampaignTarget, Long> {

    List<VoiceCampaignTarget> findByCampaignIdOrderByIdAsc(Long campaignId);

    @Query("""
            select t from VoiceCampaignTarget t
            where t.campaignId = :campaignId and t.status = com.aicallcrm.crm.voice.Voice.TargetStatus.PENDING
              and (t.nextAttemptAt is null or t.nextAttemptAt <= :now)
            order by t.id
            """)
    List<VoiceCampaignTarget> findDue(Long campaignId, Instant now, Pageable page);

    long countByCampaignIdAndStatus(Long campaignId, TargetStatus status);

    Optional<VoiceCampaignTarget> findFirstByLastCallId(UUID lastCallId);

    void deleteByCampaignId(Long campaignId);
}

interface VoiceCallRepository extends JpaRepository<VoiceCall, UUID>, JpaSpecificationExecutor<VoiceCall> {

    Optional<VoiceCall> findByProviderCallId(String providerCallId);

    /**
     * Takes the waiting desk transfer of a call: 1 for exactly one caller, even when the engine's {@code end} and the
     * platform's hang-up report arrive together; 0 if there is none (any more).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update VoiceCall c set c.transferPending = false where c.id = :id and c.transferPending = true")
    int claimTransfer(UUID id);

    List<VoiceCall> findByContactIdAndTestCallFalseOrderByCreatedAtDesc(Long contactId);

    long countByCampaignIdAndStatusIn(Long campaignId, Collection<CallStatus> statuses);

    /** Calls that really rang this number since {@code since}: test and never-dialled calls do not count. */
    @Query("""
            select c from VoiceCall c
            where c.phone = :phone and c.testCall = false and c.createdAt >= :since
              and c.direction = com.aicallcrm.crm.voice.Voice.Direction.OUTBOUND
              and c.status not in (com.aicallcrm.crm.voice.Voice.CallStatus.SIMULATED,
                                   com.aicallcrm.crm.voice.Voice.CallStatus.FAILED)
            order by c.createdAt desc
            """)
    List<VoiceCall> findRungSince(String phone, Instant since);

    long countByPhoneAndOutcomeAndTestCallFalseAndCreatedAtGreaterThanEqual(String phone, Outcome outcome,
                                                                            Instant since);

    List<VoiceCall> findByTestCallFalseAndCreatedAtGreaterThanEqual(Instant since);

    List<VoiceCall> findByStatusInAndCreatedAtBefore(Collection<CallStatus> statuses, Instant before);

    @Query("select c from VoiceCall c where c.createdAt < :before and (c.transcript is not null or c.recordingRef is not null)")
    List<VoiceCall> findWithContentBefore(Instant before, Pageable page);
}

interface VoiceToolAuditRepository extends JpaRepository<VoiceToolAudit, Long> {

    List<VoiceToolAudit> findByCallIdOrderByIdAsc(UUID callId);

    List<VoiceToolAudit> findByCreatedAtGreaterThanEqual(Instant since);
}

interface CallbackRequestRepository extends JpaRepository<CallbackRequest, Long> {

    @EntityGraph(attributePaths = "assignedTo")
    List<CallbackRequest> findByStatusInOrderByDueAtAsc(Collection<CallbackStatus> statuses);

    @EntityGraph(attributePaths = "assignedTo")
    List<CallbackRequest> findTop100ByStatusOrderByDoneAtDesc(CallbackStatus status);

    List<CallbackRequest> findByVoiceCallId(UUID voiceCallId);

    @EntityGraph(attributePaths = "assignedTo")
    List<CallbackRequest> findByContactIdAndStatusIn(Long contactId, Collection<CallbackStatus> statuses);
}

interface VoiceWebhookEventRepository extends JpaRepository<VoiceWebhookEvent, Long> {

    boolean existsByProviderCallIdAndEventType(String providerCallId, String eventType);
}

interface VoiceSettingRepository extends JpaRepository<VoiceSetting, Integer> {
}

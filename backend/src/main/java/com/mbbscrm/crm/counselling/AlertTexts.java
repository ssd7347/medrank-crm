package com.mbbscrm.crm.counselling;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.mbbscrm.crm.college.College;
import com.mbbscrm.crm.common.Language;
import com.mbbscrm.crm.common.Quota;
import com.mbbscrm.crm.engagement.CounsellingSession;
import com.mbbscrm.crm.student.Student;

/**
 * Message templates for students and parents, in the student's preferred language (spec 4.25): English,
 * Tamil or Hindi. College and round names stay in English because that is how they appear on the official
 * counselling sites. WhatsApp templates must also be pre-approved by Meta through the BSP, one per language.
 */
@Component
public class AlertTexts {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.ENGLISH).withZone(ZoneId.of("Asia/Kolkata"));

    private final String org;

    public AlertTexts(@Value("${app.org-name}") String org) {
        this.org = org;
    }

    public static String when(Instant t) {
        return t == null ? "the deadline" : WHEN.format(t) + " IST";
    }

    private static Language language(Student s) {
        return s.getLanguagePreference() == null ? Language.ENGLISH : s.getLanguagePreference();
    }

    public String choiceFillingClosing(Student s, CounsellingRoundEntity round) {
        String name = s.getFullName();
        String at = when(round.getChoiceFillingEnd());
        return switch (language(s)) {
            case TAMIL -> org + " நினைவூட்டல்: " + round.label() + " கல்லூரி விருப்பப் பதிவு " + at
                    + " அன்று முடிவடைகிறது. " + name + " அவர்களின் விருப்பங்கள் இன்னும் உறுதி செய்யப்படவில்லை. "
                    + "இன்றே உங்கள் ஆலோசகரைத் தொடர்பு கொள்ளுங்கள்.";
            case HINDI -> org + " की ओर से अनुस्मारक: " + round.label() + " की चॉइस फिलिंग " + at
                    + " को बंद हो रही है। " + name + " के विकल्प अभी लॉक नहीं हुए हैं। "
                    + "कृपया आज ही अपने काउंसलर से संपर्क करें।";
            case ENGLISH -> "Reminder from " + org + ": " + round.label() + " choice filling closes on " + at + ". "
                    + name + "'s choices are not locked yet. Please contact your counsellor today.";
        };
    }

    public String allotted(Student s, CounsellingRoundEntity round, College college, Quota quota, Instant deadline) {
        String name = s.getFullName();
        String seat = college.getName() + " (" + quota + ")";
        return switch (language(s)) {
            case TAMIL -> name + ": " + round.label() + " முடிவு - " + seat + " கல்லூரியில் இடம் ஒதுக்கப்பட்டுள்ளது. "
                    + when(deadline) + " க்குள் முடிவெடுத்து கல்லூரியில் சேர வேண்டும். "
                    + "முடிவெடுக்கும் முன் உங்கள் ஆலோசகரை அழைக்கவும். - " + org;
            case HINDI -> name + ": " + round.label() + " का परिणाम - " + seat + " में सीट आवंटित हुई है। "
                    + when(deadline) + " तक निर्णय लेकर रिपोर्ट करें। "
                    + "निर्णय से पहले अपने काउंसलर को कॉल करें। - " + org;
            case ENGLISH -> name + ": " + round.label() + " result - seat allotted at " + seat + ". Decide and report by "
                    + when(deadline) + ". Please call your counsellor before deciding. - " + org;
        };
    }

    public String notAllotted(Student s, CounsellingRoundEntity round) {
        String name = s.getFullName();
        return switch (language(s)) {
            case TAMIL -> name + ": " + round.label() + " இல் இடம் ஒதுக்கப்படவில்லை. "
                    + "அடுத்த சுற்றுக்கு உங்கள் ஆலோசகர் வழிகாட்டுவார். - " + org;
            case HINDI -> name + ": " + round.label() + " में कोई सीट आवंटित नहीं हुई। "
                    + "अगले राउंड के लिए आपका काउंसलर मार्गदर्शन करेगा। - " + org;
            case ENGLISH -> name + ": no seat was allotted in " + round.label()
                    + ". Your counsellor will guide you for the next round. - " + org;
        };
    }

    public String decisionDue(Student s, CounsellingRoundEntity round, College college, Instant deadline) {
        String name = s.getFullName();
        return switch (language(s)) {
            case TAMIL -> "அவசரம்: " + name + " அவர்கள் " + college.getName() + " இடம் (" + round.label() + ") குறித்து "
                    + when(deadline) + " க்கு முன் முடிவெடுக்க வேண்டும். உடனே உங்கள் ஆலோசகரை அழைக்கவும். - " + org;
            case HINDI -> "अत्यावश्यक: " + name + " को " + college.getName() + " की सीट (" + round.label() + ") पर "
                    + when(deadline) + " से पहले निर्णय लेना है। अभी अपने काउंसलर को कॉल करें। - " + org;
            case ENGLISH -> "Urgent: " + name + " must decide on the " + college.getName() + " seat (" + round.label()
                    + ") before " + when(deadline) + ". Call your counsellor now. - " + org;
        };
    }

    public String sessionInvite(Student s, CounsellingSession session) {
        String name = s.getFullName();
        String at = when(session.getScheduledAt());
        String host = session.getHost().getFullName();
        String topic = "\"" + session.getTopic() + "\"";
        Language lang = language(s);
        String how = switch (session.getMode()) {
            case VIDEO -> switch (lang) {
                case TAMIL -> " இணைய: " + session.getMeetingUrl();
                case HINDI -> " जुड़ें: " + session.getMeetingUrl();
                case ENGLISH -> " Join: " + session.getMeetingUrl();
            };
            case PHONE -> switch (lang) {
                case TAMIL -> " நாங்கள் உங்களை அழைப்போம்.";
                case HINDI -> " हम आपको कॉल करेंगे।";
                case ENGLISH -> " We will call you.";
            };
            case IN_PERSON -> switch (lang) {
                case TAMIL -> " எங்கள் அலுவலகத்திற்கு வாருங்கள்.";
                case HINDI -> " कृपया हमारे कार्यालय आएँ।";
                case ENGLISH -> " Please come to our office.";
            };
        };
        String head = switch (lang) {
            case TAMIL -> name + ": ஆலோசனை அமர்வு " + topic + " " + at + " அன்று, " + host + " உடன்.";
            case HINDI -> name + ": काउंसलिंग सत्र " + topic + " " + at + " को, " + host + " के साथ।";
            case ENGLISH -> name + ": counselling session " + topic + " on " + at + " with " + host + ".";
        };
        return head + how + " - " + org;
    }
}

package com.mbbscrm.crm.voice;

import com.mbbscrm.crm.voice.Voice.DndResult;

/**
 * Looks one number up against the national do-not-disturb register. The telephony provider normally offers
 * this; add a bean implementing this interface and {@link DndService} uses it instead of the built-in one.
 */
public interface DndProvider {

    /** {@code phone} is a 10-digit Indian mobile number. */
    DndResult check(String phone);
}

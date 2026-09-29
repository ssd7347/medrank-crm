package com.mbbscrm.crm.storage;

/**
 * Private file storage for student documents. Keys are generated server-side (never from user input) and
 * files are only ever served back through an authorised API call, never by a public URL.
 */
public interface FileStorage {

    void put(String key, byte[] content, String contentType);

    byte[] get(String key);

    void delete(String key);

    /** Short description for logs, e.g. "local disk at data/uploads". */
    String describe();
}

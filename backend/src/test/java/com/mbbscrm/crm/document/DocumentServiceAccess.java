package com.mbbscrm.crm.document;

/** Test bridge to package-private helpers in {@link DocumentService}. */
public final class DocumentServiceAccess {

    private DocumentServiceAccess() {
    }

    public static String sniff(byte[] bytes) {
        return DocumentService.sniff(bytes);
    }
}

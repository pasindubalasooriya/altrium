package com.altrium.export;

/**
 * The two formats scenario section 13 asks for, and they are not interchangeable.
 *
 * <p>A spreadsheet is worked on afterwards: sorted, filtered, pasted into something else. A PDF
 * is circulated as it stands and is the one that ends up attached to an email. Offering both is
 * the difference between a report somebody can use and one they have to retype.
 */
public enum ExportFormat {

    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    PDF("pdf", "application/pdf");

    private final String extension;
    private final String contentType;

    ExportFormat(String extension, String contentType) {
        this.extension = extension;
        this.contentType = contentType;
    }

    public String extension() {
        return extension;
    }

    public String contentType() {
        return contentType;
    }
}

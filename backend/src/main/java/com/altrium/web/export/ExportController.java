package com.altrium.web.export;

import com.altrium.export.ExportFormat;
import com.altrium.export.ExportService;
import com.altrium.export.PdfExporter;
import com.altrium.export.XlsxExporter;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Feature 19 - taking a report out of the system (scenario section 13).
 *
 * <p>No {@code @PreAuthorize}, as everywhere else. The gate is {@code EXPORT_REPORT} inside
 * {@link ExportService}, and it is the one capability in Altrium that refuses somebody data
 * they can already see: a manager reads every one of these numbers on their dashboard and
 * cannot export them, because a file travels where no check follows it.
 *
 * <p>One route with a format parameter rather than two routes. The two formats are the same
 * report laid out differently, and separate endpoints would eventually be given separate
 * content.
 */
@RestController
@RequestMapping("/api/exports")
public class ExportController {

    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());

    private final ExportService exports;
    private final XlsxExporter xlsx;
    private final PdfExporter pdf;

    public ExportController(ExportService exports, XlsxExporter xlsx, PdfExporter pdf) {
        this.exports = exports;
        this.xlsx = xlsx;
        this.pdf = pdf;
    }

    /**
     * A cycle report, as a spreadsheet or a PDF.
     *
     * <p>The file is named for the cycle and the day it was taken, because a downloads folder
     * with three files called {@code export.xlsx} is how the wrong one gets circulated.
     *
     * @param format {@code xlsx} or {@code pdf}; anything else is a 400, since a request for a
     *               format that does not exist is a malformed request rather than a refusal
     */
    @GetMapping("/cycles/{cycleId}")
    @Operation(summary = "Export a cycle report; HR and Leadership only, managers refused (P-8.1)")
    public ResponseEntity<byte[]> exportCycle(@PathVariable Long cycleId,
                                              @RequestParam(defaultValue = "xlsx") String format) {
        ExportFormat chosen = parse(format);

        // Built before rendering, and it is the call that decides whether this person may
        // export at all. Rendering a report and then refusing it would mean the scoped queries
        // had already run for somebody with no grounds.
        ExportService.ExportReport report = exports.buildReport(cycleId, chosen);

        byte[] body = chosen == ExportFormat.XLSX ? xlsx.render(report) : pdf.render(report);

        String filename = "altrium-" + report.cycle().label().replace(' ', '-').toLowerCase(Locale.ROOT)
                + "-" + FILE_STAMP.format(report.generatedAt()) + "." + chosen.extension();

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(chosen.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                // A report is a snapshot of a moving cycle, and a cached copy served tomorrow
                // would be wrong in a way nobody would notice. It also keeps the file out of
                // any shared cache between here and the browser.
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }

    private static ExportFormat parse(String format) {
        try {
            return ExportFormat.valueOf(format.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new com.altrium.config.ValidationApiException(
                    "Unknown export format '" + format + "'. Use xlsx or pdf.");
        }
    }
}

package com.altrium.export;

import com.altrium.review.Rating;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The PDF half of feature 19: Thymeleaf renders the page, openhtmltopdf prints it.
 *
 * <p>Two steps rather than a PDF library with a layout API, because the layout is then HTML and
 * CSS that anybody can read and change - and because the alternative is positioning table cells
 * by coordinate, which is where report code goes to die.
 *
 * <p>The template is given the same {@code ExportReport} the spreadsheet gets, so a PDF and an
 * XLSX of the same cycle cannot disagree about a number. Everything different between them is
 * layout.
 */
@Component
public class PdfExporter {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm").withZone(ZoneId.systemDefault());

    private final TemplateEngine templates;

    public PdfExporter(TemplateEngine templates) {
        this.templates = templates;
    }

    public byte[] render(ExportService.ExportReport report) {
        Context context = new Context();
        context.setVariable("report", report);
        context.setVariable("generatedAt", STAMP.format(report.generatedAt()));

        // Labelled and ordered here rather than in the template. The order is the scale's, so
        // the three rows read Needs improvement, Meets, Exceeds on every report; a map iterated
        // in whatever order it happened to have would reorder itself between cycles.
        Map<String, Long> ratings = new LinkedHashMap<>();
        for (Rating rating : Rating.values()) {
            ratings.put(label(rating), report.ratingDistribution().getOrDefault(rating, 0L));
        }
        context.setVariable("ratings", ratings);

        String html = templates.process("export/cycle-report", context);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            // No base URI. The template references no image, font or stylesheet of its own, so
            // there is nothing to resolve - and a renderer that cannot fetch anything cannot be
            // pointed at a file on the server by something that reaches the template later.
            builder.withHtmlContent(html, null);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write the export PDF", e);
        }
    }

    private static String label(Rating rating) {
        return switch (rating) {
            case NEEDS_IMPROVEMENT -> "Needs improvement";
            case MEETS_EXPECTATIONS -> "Meets expectations";
            case EXCEEDS_EXPECTATIONS -> "Exceeds expectations";
        };
    }
}

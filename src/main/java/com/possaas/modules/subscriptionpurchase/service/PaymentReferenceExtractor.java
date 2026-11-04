package com.possaas.modules.subscriptionpurchase.service;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Finds the transfer reference in bank content. Only an exact PS + 20 HEX token counts; no fuzzy or amount matching.
 * The provider-extracted code is accepted only when the content really contains it.
 */
@Component
public class PaymentReferenceExtractor {
    private static final Pattern TOKEN = Pattern.compile("(?<![A-Z0-9])PS[0-9A-F]{20}(?![A-Z0-9])");

    public enum Outcome { MATCHED, UNMATCHED, AMBIGUOUS }
    public record Extraction(Outcome outcome, String reference) {}

    public Extraction extract(String code, String content) {
        String text = content == null ? "" : content.toUpperCase(Locale.ROOT);
        Set<String> found = new LinkedHashSet<>();
        TOKEN.matcher(text).results().forEach(match -> found.add(match.group()));
        if (code != null) {
            String normalized = code.trim().toUpperCase(Locale.ROOT);
            if (PaymentReferenceGenerator.FORMAT.matcher(normalized).matches() && text.contains(normalized)) found.add(normalized);
        }
        if (found.isEmpty()) return new Extraction(Outcome.UNMATCHED, null);
        if (found.size() > 1) return new Extraction(Outcome.AMBIGUOUS, null);
        return new Extraction(Outcome.MATCHED, found.iterator().next());
    }
}

package dev.assetpipeline.adapters.out.provider;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;

final class JsonValues {

    private JsonValues() {
    }

    /** Reads a number or numeric string. Returns null for missing values and for the placeholders "None" and "-". */
    static BigDecimal decimal(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String text = node.asText().trim();
        if (text.isEmpty() || text.equalsIgnoreCase("None") || text.equals("-")) {
            return null;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

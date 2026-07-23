package com.neocopier.util;

import com.neocopier.model.Scrip;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ScripParser {

    private static final Pattern SCRIP_REF_EXPIRY_PATTERN = Pattern.compile("(\\d{2})([A-Z]{3})(\\d{2})");
    private static final Map<String, Integer> MONTH_MAP = Map.ofEntries(
            Map.entry("JAN", 1), Map.entry("FEB", 2), Map.entry("MAR", 3), Map.entry("APR", 4),
            Map.entry("MAY", 5), Map.entry("JUN", 6), Map.entry("JUL", 7), Map.entry("AUG", 8),
            Map.entry("SEP", 9), Map.entry("OCT", 10), Map.entry("NOV", 11), Map.entry("DEC", 12)
    );

    public static String mapExchangeSegment(String raw) {
        String value = (raw != null ? raw : "").toUpperCase();
        if (value.contains("NSE_FO") || value.equals("NFO")) return "NFO";
        if (value.contains("BSE_FO") || value.equals("BFO")) return "BFO";
        if (value.contains("NSE_CM") || value.equals("NSE")) return "NSE";
        if (value.contains("BSE_CM") || value.equals("BSE")) return "BSE";
        if (value.contains("MCX")) return "MCX";
        if (value.contains("CDE") || value.contains("CDS")) return "CDE";
        return raw != null && !raw.isEmpty() ? raw : "NSE";
    }

    public static String mapToNeoExchange(String exchange) {
        String val = (exchange != null ? exchange : "").toUpperCase();
        return switch (val) {
            case "NFO" -> "nse_fo";
            case "NSE" -> "nse_cm";
            case "BFO" -> "bse_fo";
            case "BSE" -> "bse_cm";
            case "MCX" -> "mcx_fo";
            case "CDE" -> "cde_fo";
            default -> "nse_cm";
        };
    }

    public static String deriveSegment(String symbol, String exchangeRaw, String instrumentName, String optionType) {
        String combined = (symbol + " " + exchangeRaw + " " + instrumentName + " " + optionType).toUpperCase();
        if (combined.contains("CE") || combined.contains("CALL")) return "CE";
        if (combined.contains("PE") || combined.contains("PUT")) return "PE";
        if (combined.contains("FUT")) return "FUT";
        return "EQ";
    }

    public static String getField(Map<String, String> row, List<String> candidates) {
        if (row == null || row.isEmpty()) return "";
        Map<String, String> lowerRow = new HashMap<>();
        for (Map.Entry<String, String> entry : row.entrySet()) {
            if (entry.getKey() != null) {
                lowerRow.put(entry.getKey().trim().toLowerCase(), entry.getValue() != null ? entry.getValue().trim() : "");
            }
        }
        for (String candidate : candidates) {
            String val = lowerRow.get(candidate.toLowerCase());
            if (val != null && !val.isEmpty()) {
                return val;
            }
        }
        return "";
    }

    /**
     * Exact expiry date parsing logic matching python backend app/scrips.py:parse_expiry_date
     */
    public static LocalDate parseExpiryDate(Map<String, String> row) {
        // 1. Match from scripRefKey regex (e.g. 30JUL26 or 26JUL24)
        String scripRef = getField(row, List.of("pscriprefkey", "scriprefkey", "scrip_ref_key")).toUpperCase();
        Matcher matcher = SCRIP_REF_EXPIRY_PATTERN.matcher(scripRef);
        if (matcher.find()) {
            String day = matcher.group(1);
            String monthName = matcher.group(2);
            String year = matcher.group(3);
            Integer month = MONTH_MAP.get(monthName);
            if (month != null) {
                try {
                    return LocalDate.of(2000 + Integer.parseInt(year), month, Integer.parseInt(day));
                } catch (Exception ignored) {}
            }
        }

        // 2. Parse raw pExpiryDate field
        String raw = getField(row, List.of("pexpirydate", "expirydate", "expiry_date", "expiry", "exp_date", "expirydatetime"));
        if (raw.isEmpty()) {
            return null;
        }

        try {
            // Epoch seconds / milliseconds
            if (raw.matches("-?\\d+")) {
                long stamp = Long.parseLong(raw) / (raw.length() == 13 ? 1000 : 1);
                return java.time.Instant.ofEpochSecond(stamp).atZone(java.time.ZoneId.systemDefault()).toLocalDate();
            }
            // ISO date string (YYYY-MM-DD...)
            if (raw.length() >= 10) {
                return LocalDate.parse(raw.substring(0, 10));
            }
        } catch (Exception ignored) {}

        // 3. Fallback formatters (e.g. 30-Jul-2026, 30-JUL-26, 30/07/2026)
        List<DateTimeFormatter> formatters = List.of(
                DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH),
                DateTimeFormatter.ofPattern("dd-MMM-yy", Locale.ENGLISH),
                DateTimeFormatter.ofPattern("dd/MM/yyyy"),
                DateTimeFormatter.ofPattern("ddMMyyyy")
        );
        String upperRaw = raw.toUpperCase();
        for (DateTimeFormatter fmt : formatters) {
            try {
                return LocalDate.parse(upperRaw, fmt);
            } catch (Exception ignored) {}
        }

        return null;
    }

    /**
     * Exact row parsing matching python backend app/scrips.py:parse_raw_row
     */
    public static Scrip parseRow(Map<String, String> row) {
        String token = getField(row, List.of("psymbol", "ptoken", "token", "scriptoken", "script_token", "scripttoken", "script token", "tokenid", "token_id", "instrument_token"));
        String tradingSymbol = getField(row, List.of("ptrdsymbol", "trdsymbol", "tradingsymbol", "trading_symbol", "psymbol", "symbol", "symbolname", "symbol_name"));
        if (token.isEmpty() || tradingSymbol.isEmpty()) {
            return null;
        }
        String scripRef = getField(row, List.of("pscriprefkey", "scriprefkey", "scrip_ref_key"));
        String instrumentName = getField(row, List.of("psymname", "symname", "symbol_name", "pinstname", "instrument_name", "instrumentname", "instname"));
        if (instrumentName.isEmpty()) {
            instrumentName = tradingSymbol;
        }
        String exchangeRaw = getField(row, List.of("pexchseg", "exchseg", "exchange_segment", "pexchange", "exchange", "segment", "segmentname"));
        String optionType = getField(row, List.of("poptiontype", "optiontype", "option_type", "opt_type"));
        String strikeRaw = getField(row, List.of("pstrikeprice", "strikeprice", "strike_price", "strike", "dstrikeprice;", "dstrikeprice"));
        String lotRaw = getField(row, List.of("ilotsize", "llotsize", "iboardlotqty", "plotsize", "lotsize", "lot_size", "boardlotqty", "boardlotquantity", "lotqty"));

        double strike = 0.0;
        try {
            strike = Double.parseDouble(strikeRaw);
            if (strike > 0) strike = strike / 100.0;
        } catch (NumberFormatException ignored) {}

        int lotSize = 1;
        try {
            lotSize = (int) Double.parseDouble(lotRaw);
            if (lotSize < 1) lotSize = 1;
        } catch (NumberFormatException ignored) {}

        return Scrip.builder()
                .scriptToken(token)
                .tradingSymbol(tradingSymbol)
                .scripRefKey(scripRef)
                .instrumentName(instrumentName)
                .exchange(mapExchangeSegment(exchangeRaw))
                .segment(deriveSegment(tradingSymbol, exchangeRaw, instrumentName, optionType))
                .strikePrice(strike)
                .expiry(parseExpiryDate(row))
                .lotSize(lotSize)
                .build();
    }
}

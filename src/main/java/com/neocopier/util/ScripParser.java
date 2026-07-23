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

    public static String deriveSegment(String symbol, String exchangeRaw, String instrumentName) {
        String combined = (symbol + " " + exchangeRaw + " " + instrumentName).toUpperCase();
        if (combined.contains("CE") || combined.contains("CALL")) return "CE";
        if (combined.contains("PE") || combined.contains("PUT")) return "PE";
        if (combined.contains("FUT")) return "FUT";
        return "EQ";
    }

    public static String getField(Map<String, String> row, List<String> candidates) {
        Map<String, String> lowerRow = new HashMap<>();
        for (Map.Entry<String, String> entry : row.entrySet()) {
            lowerRow.put(entry.getKey().trim().toLowerCase(), entry.getValue() != null ? entry.getValue().trim() : "");
        }
        for (String candidate : candidates) {
            String val = lowerRow.get(candidate.toLowerCase());
            if (val != null && !val.isEmpty()) {
                return val;
            }
        }
        return "";
    }

    public static LocalDate parseExpiryDate(Map<String, String> row) {
        String scripRef = getField(row, List.of("pscriprefkey", "scriprefkey", "scrip_ref_key")).toUpperCase();
        Matcher matcher = SCRIP_REF_EXPIRY_PATTERN.matcher(scripRef);
        if (matcher.find()) {
            String day = matcher.group(1);
            String monthStr = matcher.group(2);
            String year = matcher.group(3);
            Integer month = MONTH_MAP.get(monthStr);
            if (month != null) {
                try {
                    return LocalDate.of(2000 + Integer.parseInt(year), month, Integer.parseInt(day));
                } catch (Exception ignored) {}
            }
        }

        String raw = getField(row, List.of("pexpirydate", "expirydate", "expiry_date", "expiry", "exp_date", "expirydatetime"));
        if (raw.isEmpty()) {
            return null;
        }
        try {
            if (raw.matches("\\d+")) {
                long stamp = Long.parseLong(raw) / (raw.length() == 13 ? 1000 : 1);
                return LocalDate.ofEpochDay(stamp / 86400);
            }
            return LocalDate.parse(raw.substring(0, 10), DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (Exception e) {
            return null;
        }
    }

    public static Scrip parseRow(Map<String, String> row) {
        String token = getField(row, List.of("psymbol", "ptoken", "token", "scriptoken", "script_token", "tokenid", "instrument_token"));
        String tradingSymbol = getField(row, List.of("ptrdsymbol", "trdsymbol", "tradingsymbol", "trading_symbol", "psymbol", "symbol", "symbolname"));
        if (token.isEmpty() || tradingSymbol.isEmpty()) {
            return null;
        }
        String scripRef = getField(row, List.of("pscriprefkey", "scriprefkey", "scrip_ref_key"));
        String instrumentName = getField(row, List.of("psymname", "symname", "symbol_name", "pinstname", "instrument_name", "instrumentname"));
        if (instrumentName.isEmpty()) {
            instrumentName = tradingSymbol;
        }
        String exchangeRaw = getField(row, List.of("pexchseg", "exchseg", "exchange_segment", "pexchange", "exchange", "segment"));
        String strikeRaw = getField(row, List.of("pstrikeprice", "strikeprice", "strike_price", "strike", "dstrikeprice"));
        String lotRaw = getField(row, List.of("ilotsize", "llotsize", "plotsize", "lotsize", "lot_size", "boardlotqty"));

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
                .segment(deriveSegment(tradingSymbol, exchangeRaw, instrumentName))
                .strikePrice(strike)
                .expiry(parseExpiryDate(row))
                .lotSize(lotSize)
                .build();
    }
}

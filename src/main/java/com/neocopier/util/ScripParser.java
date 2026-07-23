package com.neocopier.util;

import com.neocopier.model.Scrip;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ScripParser {

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

    public static LocalDate parseExpiryDate(Map<String, String> row) {
        String raw = getField(row, List.of("pexpirydate", "expirydate", "expiry_date", "expiry", "exp_date", "expirydatetime", "expiry_time"));
        String scripRef = getField(row, List.of("pscriprefkey", "scriprefkey", "scrip_ref_key")).toUpperCase();
        String trdSym = getField(row, List.of("ptrdsymbol", "trdsymbol", "tradingsymbol", "trading_symbol")).toUpperCase();

        // 1. Try parsing raw expiry field
        if (raw != null && !raw.trim().isEmpty()) {
            String trimmed = raw.trim();
            // Check numeric epoch
            if (trimmed.matches("-?\\d+")) {
                try {
                    long val = Long.parseLong(trimmed);
                    if (val > 0) {
                        long epochSec = val > 10_000_000_000L ? val / 1000 : val;
                        return java.time.Instant.ofEpochSecond(epochSec).atZone(java.time.ZoneId.systemDefault()).toLocalDate();
                    }
                } catch (Exception ignored) {}
            }
            // Try common date formatters
            List<DateTimeFormatter> formatters = List.of(
                    DateTimeFormatter.ISO_LOCAL_DATE, // 2026-07-30
                    DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH), // 30-Jul-2026 / 30-JUL-2026
                    DateTimeFormatter.ofPattern("dd-MMM-yy", Locale.ENGLISH), // 30-JUL-26
                    DateTimeFormatter.ofPattern("dd/MM/yyyy"), // 30/07/2026
                    DateTimeFormatter.ofPattern("ddMMyyyy"), // 30072026
                    DateTimeFormatter.ofPattern("yyyyMMdd"), // 20260730
                    DateTimeFormatter.ofPattern("ddMMMyyyy", Locale.ENGLISH) // 30JUL2026
            );
            String upperRaw = trimmed.substring(0, Math.min(trimmed.length(), 11)).toUpperCase();
            for (DateTimeFormatter fmt : formatters) {
                try {
                    return LocalDate.parse(upperRaw, fmt);
                } catch (Exception ignored) {}
            }
        }

        // 2. Try parsing from scripRef or tradingSymbol using regex
        String textToSearch = (scripRef + " " + trdSym).toUpperCase();

        // Match DDMMMYY or DDMMMYYYY (e.g. 30JUL26 or 30JUL2026)
        Matcher m1 = Pattern.compile("(\\d{2})([A-Z]{3})(\\d{2,4})").matcher(textToSearch);
        if (m1.find()) {
            String dayStr = m1.group(1);
            String monthStr = m1.group(2);
            String yearStr = m1.group(3);
            Integer month = MONTH_MAP.get(monthStr);
            if (month != null) {
                try {
                    int day = Integer.parseInt(dayStr);
                    int year = Integer.parseInt(yearStr);
                    if (year < 100) year += 2000;
                    return LocalDate.of(year, month, day);
                } catch (Exception ignored) {}
            }
        }

        // Match YYMMM (e.g. 26JUL for year 2026 month July)
        Matcher m2 = Pattern.compile("(\\d{2})([A-Z]{3})").matcher(textToSearch);
        if (m2.find()) {
            String yearStr = m2.group(1);
            String monthStr = m2.group(2);
            Integer month = MONTH_MAP.get(monthStr);
            if (month != null) {
                try {
                    int year = 2000 + Integer.parseInt(yearStr);
                    return LocalDate.of(year, month, 1).plusMonths(1).minusDays(1);
                } catch (Exception ignored) {}
            }
        }

        return null;
    }

    public static Scrip parseRow(Map<String, String> row) {
        String token = getField(row, List.of("ptoken", "token", "scriptoken", "script_token", "tokenid", "instrument_token", "psymbol"));
        String tradingSymbol = getField(row, List.of("ptrdsymbol", "trdsymbol", "tradingsymbol", "trading_symbol", "psymbolname", "symbolname", "symbol"));
        if (token.isEmpty() || tradingSymbol.isEmpty()) {
            return null;
        }
        String scripRef = getField(row, List.of("pscriprefkey", "scriprefkey", "scrip_ref_key"));
        String instrumentName = getField(row, List.of("psymname", "symname", "symbol_name", "pinstname", "instrument_name", "instrumentname"));
        if (instrumentName.isEmpty()) {
            instrumentName = tradingSymbol;
        }
        String exchangeRaw = getField(row, List.of("pexchseg", "exchseg", "exchange_segment", "pexchange", "exchange", "segment"));
        String optionType = getField(row, List.of("poptiontype", "optiontype", "option_type", "opt_type"));
        String strikeRaw = getField(row, List.of("pstrikeprice", "strikeprice", "strike_price", "strike", "dstrikeprice"));
        String lotRaw = getField(row, List.of("ilotsize", "llotsize", "plotsize", "lotsize", "lot_size", "boardlotqty"));

        double strike = 0.0;
        try {
            strike = Double.parseDouble(strikeRaw);
            if (strike > 0 && strikeRaw.length() > 6 && !strikeRaw.contains(".")) {
                strike = strike / 100.0;
            }
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

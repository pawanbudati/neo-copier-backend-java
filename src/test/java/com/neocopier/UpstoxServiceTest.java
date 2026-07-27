package com.neocopier;

import com.neocopier.model.Scrip;
import com.neocopier.service.UpstoxService;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;

public class UpstoxServiceTest {

    @Test
    public void testScripRefKeyStrikeExtraction() throws Exception {
        UpstoxService upstoxService = new UpstoxService();
        upstoxService.init();

        System.out.println("[TEST] Waiting 6 seconds for complete.csv.gz indexing...");
        Thread.sleep(6000);

        // Test 1: BankNifty Option with strikePrice = 0.0, refKey = BANKNIFTY25AUG2639700.00PE
        Scrip s1 = new Scrip("35177", "BANKNIFTY26AUG39700PE", "BANKNIFTY25AUG2639700.00PE", "OPTIDX", "NFO", "PE", 0.0, null, 30);
        String key1 = upstoxService.resolveInstrumentKey("35177", s1);
        System.out.println("[TEST 1 - BANKNIFTY 39700 PE] Key = " + key1);

        // Test 2: Nifty Option with strikePrice = 0.0, refKey = NIFTY18AUG2628750.00CE
        Scrip s2 = new Scrip("45347", "NIFTY2681828750CE", "NIFTY18AUG2628750.00CE", "OPTIDX", "NFO", "CE", 0.0, null, 65);
        String key2 = upstoxService.resolveInstrumentKey("45347", s2);
        System.out.println("[TEST 2 - NIFTY 28750 CE] Key = " + key2);

        // Test 3: Sensex Option with strikePrice = 0.0, refKey = SENSEX26JUL2477000.00PE
        Scrip s3 = new Scrip("99999", "SENSEX26JUL77000PE", "SENSEX26JUL2477000.00PE", "OPTIDX", "BFO", "PE", 0.0, null, 10);
        String key3 = upstoxService.resolveInstrumentKey("99999", s3);
        System.out.println("[TEST 3 - SENSEX 77000 PE] Key = " + key3);

        // Test 4: Sensex Index
        Scrip s4 = new Scrip("1", "SENSEX", "SENSEX", "INDEX", "BSE", "INDEX", 0.0, null, 1);
        String key4 = upstoxService.resolveInstrumentKey("1", s4);
        System.out.println("[TEST 4 - SENSEX INDEX] Key = " + key4);

        // Fetch candles for Nifty option
        List<Map<String, Object>> candles = upstoxService.fetchHistoricalCandles(key2, "1m");
        System.out.println("[TEST 2] Historical Candles count: " + candles.size());
        if (!candles.isEmpty()) {
            System.out.println("[TEST 2] Sample option candle: " + candles.get(0));
        }
    }
}

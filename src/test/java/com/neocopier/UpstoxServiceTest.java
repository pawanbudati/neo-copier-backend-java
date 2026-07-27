package com.neocopier;

import com.neocopier.model.Scrip;
import com.neocopier.service.UpstoxService;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;

public class UpstoxServiceTest {

    @Test
    public void testUpstoxCandles() throws Exception {
        UpstoxService upstoxService = new UpstoxService();
        upstoxService.init();

        System.out.println("[TEST] Waiting for complete.csv.gz indexing...");
        Thread.sleep(6000);

        // Test 1: SENSEX 77000 PE Option
        Scrip sensexOpt = new Scrip("12345", "SENSEX26JUL77000PE", "SENSEX26JUL77000PE", "SENSEX", "BFO", "PE", 77000.0, null, 10);
        String sensexKey = upstoxService.resolveInstrumentKey("12345", sensexOpt);
        System.out.println("[TEST 1 - SENSEX OPTION] Key=" + sensexKey);

        // Test 2: SENSEX Index
        Scrip sensexIndex = new Scrip("1", "SENSEX", "SENSEX", "SENSEX", "BSE", "INDEX", 0.0, null, 1);
        String sensexIndexKey = upstoxService.resolveInstrumentKey("1", sensexIndex);
        System.out.println("[TEST 2 - SENSEX INDEX] Key=" + sensexIndexKey);

        // Test 3: Nifty 24000 CE Option
        Scrip niftyOpt = new Scrip("50978", "NIFTY26DEC27000CE", "NIFTY26DEC27000CE", "NIFTY", "NFO", "CE", 27000.0, null, 65);
        String niftyOptKey = upstoxService.resolveInstrumentKey("50978", niftyOpt);
        System.out.println("[TEST 3 - NIFTY OPTION] Key=" + niftyOptKey);

        // Test 4: Nifty Index
        Scrip niftyIndex = new Scrip("2", "NIFTY 50", "NIFTY 50", "NIFTY 50", "NSE", "INDEX", 0.0, null, 1);
        String niftyIndexKey = upstoxService.resolveInstrumentKey("2", niftyIndex);
        System.out.println("[TEST 4 - NIFTY INDEX] Key=" + niftyIndexKey);

        // Fetch candles for Nifty Option
        List<Map<String, Object>> candles = upstoxService.fetchHistoricalCandles(niftyOptKey, "1m");
        System.out.println("[TEST 3] Option Candles returned count: " + candles.size());
        if (!candles.isEmpty()) {
            System.out.println("[TEST 3] Sample option candle: " + candles.get(0));
        }
    }
}

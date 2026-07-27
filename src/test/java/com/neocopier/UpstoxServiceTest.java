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

        Scrip scrip = new Scrip("50978", "NIFTY26DEC27000CE", "NIFTY26DEC27000CE", "NIFTY26DEC27000CE", "NFO", "CE", 27000.0, null, 65);
        String resolvedKey = upstoxService.resolveInstrumentKey("50978", scrip);
        System.out.println("[TEST] Resolved instrument key: " + resolvedKey);

        List<Map<String, Object>> candles = upstoxService.fetchHistoricalCandles(resolvedKey, "1m");
        System.out.println("[TEST] Historical Candles returned count: " + candles.size());
        if (!candles.isEmpty()) {
            System.out.println("[TEST] Sample candle: " + candles.get(0));
        }
    }
}

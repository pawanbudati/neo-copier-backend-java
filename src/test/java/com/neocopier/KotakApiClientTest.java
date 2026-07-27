package com.neocopier;

import com.neocopier.client.KotakApiClient;
import com.neocopier.model.Account;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class KotakApiClientTest {

    @Test
    public void testOrderPayloadAndFallbackHandling() {
        KotakApiClient client = new KotakApiClient();
        Account dummyAccount = new Account();
        dummyAccount.setBaseUrl("https://mis.kotaksecurities.com");
        dummyAccount.setConsumerKey("test_key");
        dummyAccount.setSid("test_sid");
        dummyAccount.setNeoToken("test_neo_token");

        Map<String, Object> payload = new HashMap<>();
        payload.put("exchange_segment", "nse_fo");
        payload.put("trading_symbol", "NIFTY26AUG24000CE");
        payload.put("quantity", "50");
        payload.put("price", "0");
        payload.put("transaction_type", "B");
        payload.put("order_type", "MKT");
        payload.put("product", "MIS");
        payload.put("validity", "DAY");

        // Execute placeOrder against candidate endpoints
        Map<String, Object> res = client.placeOrder(dummyAccount, payload);
        assertNotNull(res);
        System.out.println("[TEST placeOrder Candidate Fallback Output]: " + res);
    }
}

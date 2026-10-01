package ir.digigharz.sms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ir.digigharz.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SmsSenderTest {
  record Call(String method, String url, Map<String, String> headers, String body) {}

  static final class Fake implements SmsSender.Transport {
    final List<Call> calls = new ArrayList<>();
    final int status;
    final String reply;

    Fake(int status, String reply) {
      this.status = status;
      this.reply = reply;
    }

    public SmsSender.Response request(String method, String url, Map<String, String> headers, String body) {
      calls.add(new Call(method, url, headers, body));
      return new SmsSender.Response(status, Json.parse(reply));
    }
  }

  @Test
  void noProviderKeyMeansDevMode() {
    assertNull(SmsSender.fromEnv(Map.of()));
  }

  @Test
  void smsIrSendsTheCodeThroughItsVerifyTemplate() {
    Fake fake = new Fake(200, "{\"status\":1,\"message\":\"موفق\"}");
    SmsSender send = SmsSender.fromEnv(Map.of("SMSIR_API_KEY", "k", "SMSIR_TEMPLATE_ID", "123456"), fake);
    send.send("09121234567", "04821");
    assertEquals("https://api.sms.ir/v1/send/verify", fake.calls.get(0).url());
    assertEquals("k", fake.calls.get(0).headers().get("x-api-key"));
    assertEquals(
        Json.parse("{\"mobile\":\"09121234567\",\"templateId\":123456,\"parameters\":[{\"name\":\"CODE\",\"value\":\"04821\"}]}"),
        Json.parse(fake.calls.get(0).body()));
  }

  @Test
  void smsIrFailuresSurfaceAsErrors() {
    Fake fake = new Fake(200, "{\"status\":0,\"message\":\"قالب یافت نشد\"}");
    SmsSender send = SmsSender.fromEnv(Map.of("SMSIR_API_KEY", "k", "SMSIR_TEMPLATE_ID", "1"), fake);
    assertTrue(assertThrows(IllegalStateException.class, () -> send.send("09121234567", "11111")).getMessage().contains("قالب یافت نشد"));
    // A key without an approved template yet: SMS stays off instead of the server refusing to start.
    assertNull(SmsSender.fromEnv(Map.of("SMSIR_API_KEY", "k")));
  }

  @Test
  void theSmsIrSandboxUsesItsBuiltInTemplateAndFlagsItself() {
    Fake fake = new Fake(200, "{\"status\":1,\"message\":\"موفق\",\"data\":{\"messageId\":1,\"cost\":1}}");
    SmsSender send = SmsSender.fromEnv(Map.of("SMSIR_API_KEY", "k", "SMSIR_SANDBOX", "true"), fake);
    assertTrue(send.sandbox());
    send.send("09121234567", "12345");
    assertEquals(123456, Json.parse(fake.calls.get(0).body()).get("templateId").asInt());
  }

  @Test
  void smsIrWinsOverKavenegarWhenBothAreSet() {
    Fake fake = new Fake(200, "{\"status\":1}");
    SmsSender send = SmsSender.fromEnv(Map.of("SMSIR_API_KEY", "k", "SMSIR_TEMPLATE_ID", "1", "KAVENEGAR_API_KEY", "x"), fake);
    send.send("09121234567", "11111");
    assertTrue(fake.calls.get(0).url().contains("sms.ir"));
  }

  @Test
  void theAdminPanelCanReadTheSmsIrCreditOrItsExactError() {
    Fake ok = new Fake(200, "{\"status\":1,\"message\":\"موفق\",\"data\":1250.5}");
    SmsSender send = SmsSender.fromEnv(Map.of("SMSIR_API_KEY", "k", "SMSIR_TEMPLATE_ID", "7"), ok);
    assertEquals(Json.obj("provider", "sms.ir", "sandbox", false, "templateId", 7, "parameter", "CODE"), send.info());
    assertEquals(Map.of("credit", 1250.5), send.status());
    assertEquals("https://api.sms.ir/v1/credit", ok.calls.get(0).url());

    Fake bad = new Fake(401, "{\"status\":0,\"message\":\"کلید نامعتبر است\"}");
    SmsSender send2 = SmsSender.fromEnv(Map.of("SMSIR_API_KEY", "x", "SMSIR_TEMPLATE_ID", "7"), bad);
    assertTrue(assertThrows(IllegalStateException.class, send2::status).getMessage().contains("کلید نامعتبر است"));
  }

  @Test
  void kavenegarUsesItsVerifyLookup() {
    Fake fake = new Fake(200, "{}");
    SmsSender send = SmsSender.fromEnv(Map.of("KAVENEGAR_API_KEY", "abc"), fake);
    send.send("09121234567", "12345");
    assertEquals(
        "https://api.kavenegar.com/v1/abc/verify/lookup.json?receptor=09121234567&token=12345&template=sandogh-login",
        fake.calls.get(0).url());
  }
}

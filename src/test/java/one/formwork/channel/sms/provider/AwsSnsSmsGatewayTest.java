package one.formwork.channel.sms.provider;

import one.formwork.channel.sms.api.SmsChannelProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AwsSnsSmsGatewayTest {

    private AwsSnsSmsGateway createGateway() {
        SmsChannelProperties.AwsSnsProperties config = new SmsChannelProperties.AwsSnsProperties();
        config.setRegion("eu-central-1");
        config.setSenderId("TestApp");
        return new AwsSnsSmsGateway(config);
    }

    @Test
    void supports_AwsSns_ReturnsTrue() {
        assertTrue(createGateway().supports("AWS_SNS"));
    }

    @Test
    void supports_AwsSnsCaseInsensitive_ReturnsTrue() {
        assertTrue(createGateway().supports("aws_sns"));
    }

    @Test
    void supports_OtherProvider_ReturnsFalse() {
        assertFalse(createGateway().supports("TWILIO"));
    }

    @Test
    void getProviderName_ReturnsExpected() {
        assertEquals("AWS_SNS", createGateway().getProviderName());
    }

    @Test
    void encode_Space_ProducesPercent20NotPlus() {
        // URLEncoder would produce "Hello+World"; SigV4 requires "Hello%20World"
        String result = createGateway().encode("Hello World");
        assertEquals("Hello%20World", result);
    }

    @Test
    void encode_Tilde_IsLeftUnescaped() {
        // URLEncoder would produce "%7E"; SigV4 treats '~' as unreserved
        String result = createGateway().encode("~test");
        assertEquals("~test", result);
    }

    @Test
    void encode_Asterisk_IsPercentEncoded() {
        // URLEncoder leaves '*' as a literal character; SigV4 requires it encoded
        String result = createGateway().encode("a*b");
        assertEquals("a%2Ab", result);
    }

    @Test
    void encode_UnreservedCharacters_AreUnchanged() {
        // Letters, digits, '-', '_', '.', '~' must pass through unescaped
        String input = "AZaz09-_.~";
        assertEquals(input, createGateway().encode(input));
    }

    @Test
    void encode_HexDigits_AreUppercase() {
        // SigV4 requires uppercase hex in percent-encoding (e.g. %2A not %2a)
        String result = createGateway().encode("*");
        assertEquals("%2A", result);
    }

    @Test
    void shortGsm7Message_IsOneSegment() {
        // 17 plain ASCII chars — fits easily in one 160-char GSM-7 segment
        assertEquals(1, AwsSnsSmsGateway.calculateSegmentCount("Your OTP is 1234"));
    }

    @Test
    void exactly160Gsm7Chars_IsOneSegment() {
        String body = "a".repeat(160);
        assertEquals(1, AwsSnsSmsGateway.calculateSegmentCount(body));
    }

    @Test
    void oneCharOver160Gsm7_IsTwoSegments() {
        // 161 chars — one char over the single-segment limit, must split
        String body = "a".repeat(161);
        assertEquals(2, AwsSnsSmsGateway.calculateSegmentCount(body));
    }

    @Test
    void longGsm7Message_UsesConcatenatedLimitOf153() {
        // 300 chars: 300 / 153 = 1.96 -> needs 2 segments
        String body = "a".repeat(300);
        assertEquals(2, AwsSnsSmsGateway.calculateSegmentCount(body));
    }

    @Test
    void messageWithEmoji_ForcesUcs2_LowersLimitTo70() {
        // Any non-GSM-7 character (like an emoji) forces UCS-2 encoding
        String body = "a".repeat(70) + "\uD83D\uDE00"; // 70 letters + 1 emoji = 71 UCS-2 chars
        assertEquals(2, AwsSnsSmsGateway.calculateSegmentCount(body));
    }
}

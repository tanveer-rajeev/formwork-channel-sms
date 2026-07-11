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
}

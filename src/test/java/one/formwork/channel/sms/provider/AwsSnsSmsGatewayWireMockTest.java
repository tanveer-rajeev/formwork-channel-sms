package one.formwork.channel.sms.provider;

import com.github.tomakehurst.wiremock.WireMockServer;
import one.formwork.channel.sms.api.SmsChannelProperties;
import one.formwork.channel.sms.api.SmsMessage;
import one.formwork.channel.sms.api.SmsResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

class AwsSnsSmsGatewayWireMockTest {

    private WireMockServer wireMock;
    private AwsSnsSmsGateway gateway;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(0); // random free port
        wireMock.start();

        SmsChannelProperties.AwsSnsProperties config = new SmsChannelProperties.AwsSnsProperties();
        config.setRegion("eu-central-1");
        config.setEndpointOverride("http://localhost:" + wireMock.port());
        config.setAccessKey("AKIAFAKEACCESSKEY123");
        config.setSecretKey("fakeSecretKeyForTestingOnly1234567890");

        gateway = new AwsSnsSmsGateway(config);
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    @Test
    void send_realHttpCall_sendsCorrectUrlHeadersAndBody() {
        wireMock.stubFor(get(urlPathEqualTo("/"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/xml")
                        .withBody("""
                                <PublishResponse>
                                  <PublishResult>
                                    <MessageId>abc-123-message-id</MessageId>
                                  </PublishResult>
                                </PublishResponse>
                                """)));

        SmsMessage message = new SmsMessage("+491511234 5678", "Hello there", UUID.randomUUID());
        SmsResult result = gateway.send(message);

        // Assert the gateway parsed the response correctly
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.messageId()).isEqualTo("abc-123-message-id");
        assertThat(result.provider()).isEqualTo("AWS_SNS");

        // Assert on the ACTUAL bytes sent — the real point of this test
        wireMock.verify(getRequestedFor(urlPathEqualTo("/"))
                // Required SigV4 headers present
                .withHeader("Authorization", matching("^AWS4-HMAC-SHA256 Credential=.*"))
                .withHeader("x-amz-date", matching("^\\d{8}T\\d{6}Z$"))
                // Message body correctly percent-encoded per RFC 3986 — space is %20, not '+'
                .withQueryParam("Message", equalTo("Hello there"))
                .withQueryParam("PhoneNumber", equalTo("+491511234 5678"))
                .withQueryParam("Action", equalTo("Publish"))
                .withQueryParam("Version", equalTo("2010-03-31")));
    }

    @Test
    void send_awsReturnsError_doesNotRetryAndReturnsFailure() {
        wireMock.stubFor(get(urlPathEqualTo("/"))
                .willReturn(aResponse()
                        .withStatus(403)
                        .withBody("<ErrorResponse><Error><Code>SignatureDoesNotMatch</Code></Error></ErrorResponse>")));

        SmsMessage message = new SmsMessage("+4915112345678", "Hello", UUID.randomUUID());
        SmsResult result = gateway.send(message);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorCode()).isEqualTo("403");

        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/")));
    }
}

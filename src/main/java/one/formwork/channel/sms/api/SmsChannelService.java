package one.formwork.channel.sms.api;

import one.formwork.channel.sms.cost.SmsCostService;
import one.formwork.channel.sms.validation.PhoneNumberValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class SmsChannelService {
    private static final Logger log = LoggerFactory.getLogger(SmsChannelService.class);

    private final List<SmsGateway> gateways;
    private final SmsChannelProperties properties;
    private final SmsCostService costService;

    public SmsChannelService(List<SmsGateway> gateways, SmsChannelProperties properties, SmsCostService costService) {
        this.gateways = gateways;
        this.properties = properties;
        this.costService = costService;
    }

    public SmsResult sendSms(SmsMessage message) {
        PhoneNumberValidator.validate(message.to());
        SmsGateway gateway = resolveGateway();
        SmsResult result = gateway.send(message);
        recordCostSafely(message, result);
        return result;
    }

    public List<SmsResult> sendBulk(List<SmsMessage> messages) {
        return messages.stream().map(this::sendSms).toList();
    }

    public void handleDeliveryCallback(String provider, Map<String, String> params) {
        // Provider-specific callback handling
    }

    private SmsGateway resolveGateway() {
        String providerType = properties.getProvider();
        return gateways.stream()
                .filter(g -> g.supports(providerType))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No SmsGateway for provider: " + providerType));
    }

    /**
     * Cost recording must never mask a send that already succeeded (or failed)
     * with an unrelated persistence error. We isolate failures here and log them
     * loudly instead of letting them propagate.
     */
    private void recordCostSafely(SmsMessage message, SmsResult result) {
        try {
            costService.recordCost(message.tenantId(), message.to(), result);
        } catch (Exception e) {
            log.error("Failed to record SMS cost for tenant={}, provider={}, messageId={}",
                    message.tenantId(), result.provider(), result.messageId(), e);
        }
    }
}

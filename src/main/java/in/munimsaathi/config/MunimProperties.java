package in.munimsaathi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "munim")
public record MunimProperties(
        String timezone,
        String currency,
        Meta meta,
        Supabase supabase,
        Whatsapp whatsapp,
        Tally tally,
        Eway eway,
        Reminders reminders
) {
    public record Meta(String apiKey, String baseUrl, String reasoningModel, String transcriptionModel) {
    }

    public record Supabase(String url, String anonKey, String serviceRoleKey, String jwtSecret, String storageBucket) {
    }

    public record Whatsapp(
            String accessToken,
            String appSecret,
            String verifyToken,
            String phoneNumberId,
            String businessAccountId,
            String graphApiVersion,
            boolean autoReply,
            String ownerPhone,
            String ownerBusiness
    ) {
    }

    public record Tally(boolean enabled, String connectorToken) {
    }

    public record Eway(
            boolean enabled,
            String environment,
            String baseUrl,
            String clientId,
            String clientSecret,
            String gstin,
            String username,
            String password
    ) {
    }

    public record Reminders(int graceMinutes) {
    }
}

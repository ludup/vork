package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Input schema for the {@code sendNotification} tool.
 *
 * <p>Call {@code listNotificationProviders} first to obtain the available
 * {@code providerConfigId} values and their supported media types.
 */
public record SendNotificationRequest(

        @JsonProperty(required = true, value = "providerConfigId")
        @JsonPropertyDescription(
                "UUID of the NotificationProviderConfig to use for delivery. "
                + "Obtain this from listNotificationProviders.")
        String providerConfigId,

        @JsonProperty(required = true, value = "title")
        @JsonPropertyDescription("Subject or headline of the notification.")
        String title,

        @JsonProperty(required = true, value = "body")
        @JsonPropertyDescription("Plain-text body of the notification.")
        String body,

        @JsonProperty(required = true, value = "address")
        @JsonPropertyDescription(
                "Delivery address appropriate for the chosen provider: "
                + "an email address for email providers, "
                + "or a phone number in E.164 format (e.g. +14155552671) for SMS providers.")
        String address
) {}

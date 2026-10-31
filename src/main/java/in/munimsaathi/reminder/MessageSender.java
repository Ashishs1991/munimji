package in.munimsaathi.reminder;

public interface MessageSender {

    ProviderReceipt send(String contact, String templateName, String payload);
}

package in.munimsaathi.whatsapp;

import in.munimsaathi.reminder.ProviderReceipt;

public interface SessionReply {

    ProviderReceipt sendText(String phone, String text);

    byte[] download(String mediaId);
}

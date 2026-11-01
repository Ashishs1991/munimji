package in.munimsaathi.interpretation;

public interface LanguageModel {

    String complete(String userText);

    String completeDocument(String instruction, String mediaType, String fileName, byte[] content);
}

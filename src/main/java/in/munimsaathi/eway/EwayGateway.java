package in.munimsaathi.eway;

public interface EwayGateway {

    EwayReply generate(String requestJson);

    EwayReply lookup(String docType, String docNo);
}

package in.munimsaathi.whatsapp;

import in.munimsaathi.catalog.CatalogService;
import in.munimsaathi.catalog.PartyBook;
import in.munimsaathi.catalog.PartyRole;
import in.munimsaathi.config.MunimProperties;
import in.munimsaathi.tenant.Business;
import in.munimsaathi.tenant.BusinessRepository;
import in.munimsaathi.tenant.Godown;
import in.munimsaathi.tenant.GodownRepository;
import in.munimsaathi.tenant.Membership;
import in.munimsaathi.tenant.MembershipRepository;
import in.munimsaathi.tenant.MembershipRole;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Component
public class OwnerDesk implements ApplicationRunner {

    private final MunimProperties properties;
    private final BusinessRepository businesses;
    private final MembershipRepository memberships;
    private final GodownRepository godowns;
    private final WhatsAppSenderRepository senders;
    private final WhatsAppIngress ingress;
    private final CatalogService catalog;
    private final PartyBook parties;

    public OwnerDesk(
            MunimProperties properties,
            BusinessRepository businesses,
            MembershipRepository memberships,
            GodownRepository godowns,
            WhatsAppSenderRepository senders,
            WhatsAppIngress ingress,
            CatalogService catalog,
            PartyBook parties
    ) {
        this.properties = properties;
        this.businesses = businesses;
        this.memberships = memberships;
        this.godowns = godowns;
        this.senders = senders;
        this.ingress = ingress;
        this.catalog = catalog;
        this.parties = parties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        MunimProperties.Whatsapp whatsapp = properties.whatsapp();
        if (whatsapp == null || whatsapp.ownerPhone() == null || whatsapp.ownerPhone().isBlank()) {
            return;
        }
        String phone = whatsapp.ownerPhone().replaceAll("\\D", "");
        if (phone.isBlank() || senders.findByPhone(phone).isPresent()) {
            return;
        }
        UUID userId = UUID.randomUUID();
        String name = whatsapp.ownerBusiness() == null || whatsapp.ownerBusiness().isBlank()
                ? "City Stores"
                : whatsapp.ownerBusiness().trim();
        Business business = businesses.save(new Business(UUID.randomUUID(), name, "Asia/Kolkata", "INR"));
        memberships.save(new Membership(UUID.randomUUID(), business.getId(), userId, MembershipRole.OWNER));
        godowns.save(new Godown(UUID.randomUUID(), business.getId(), "g1", "Godown 1"));
        ingress.allow(userId, phone);
        var product = catalog.registerProduct(userId, "OFFICE-FOLDER", "Office folder", null, null, "piece", false);
        catalog.approveUnit(userId, product.getId(), "box", new BigDecimal("10"));
        parties.create(userId, "Agarwal", PartyRole.CUSTOMER);
    }
}

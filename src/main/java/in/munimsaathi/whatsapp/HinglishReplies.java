package in.munimsaathi.whatsapp;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Localize fixed interface phrases without asking a model to rewrite financial facts. */
final class HinglishReplies {
    private static final List<Map.Entry<String, String>> PHRASES = List.of(
            Map.entry("Namaste. Tell me what was received, sold, or paid.", "Namaste! Maal aaya, bika, paise mile ya diye? Bataiye."),
            Map.entry("Reply Yes to post this draft only: ", "Is entry ko confirm karne ke liye Haan ya Yes bhejiye: "),
            Map.entry("Yes approves only one current proposal.", "Abhi kis entry ko confirm karna hai? Ek saaf pending entry honi chahiye."),
            Map.entry("Cancelled the balance question. Nothing was posted.", "Balance wala sawal cancel ho gaya. Koi entry post nahi hui."),
            Map.entry("Cancelled. Nothing was posted.", "Cancel ho gaya. Koi entry post nahi hui."),
            Map.entry("There is no open draft to cancel.", "Cancel karne ke liye koi pending entry nahi hai."),
            Map.entry("Which draft should I cancel?", "Kaunsi pending entry cancel karni hai?"),
            Map.entry("Still need ", "Abhi yeh batana hai: "), Map.entry("So far: ", "Ab tak: "),
            Map.entry("Posted. ", "Entry record ho gayi. "),
            Map.entry("the party", "party ka naam"), Map.entry("the product", "maal ka naam"),
            Map.entry("the godown", "godown"), Map.entry("the quantity", "quantity"),
            Map.entry("the amount", "rakam"), Map.entry("the price", "keemat"),
            Map.entry("whether this amount is new", "yeh naya amount hai ya purana bakaya"),
            Map.entry("whether this is a receipt, a sale, or a payment", "maal aaya, bika, paise mile ya diye"),
            Map.entry("Is this a new amount you owe, or an existing balance?", "Yeh naya bakaya hai ya pehle se dene hain?"),
            Map.entry("Is this a new amount you gave, or an existing balance?", "Yeh naya udhaar diya tha ya purana bakaya hai?"),
            Map.entry("Payment is larger than the supplier balance.", "Record mein itna bakaya nahi hai. Yeh advance tha ya purana bill abhi record karna baaki hai?"),
            Map.entry("Cash or UPI?", "Cash se ya UPI se?"),
            Map.entry("Who was this?", "Kis party ki baat kar rahe hain?"),
            Map.entry("Say create if I should add this party.", "Nayi party jodni hai toh create aur naam bataiye."),
            Map.entry("Record money received from ", "Paise mile hain — "),
            Map.entry("Money received from ", "Paise milne ki entry — "),
            Map.entry("Will reduce their receivable by ", "Unka bakaya itna ghatega: "),
            Map.entry("Reduced their receivable by ", "Unka bakaya itna ghata: "),
            Map.entry("Record a new receivable: ", "Naya lene wala bakaya: "),
            Map.entry("New receivable recorded: ", "Naya lene wala bakaya record ho gaya: "),
            Map.entry(" owes you ", " se aapko lene hain "),
            Map.entry("Record a supplier obligation: you owe ", "Naya dene wala bakaya: aapko dene hain — "),
            Map.entry("Supplier obligation recorded: you owe ", "Dene wala bakaya record ho gaya: aapko dene hain — "),
            Map.entry("This will increase what they owe by ", "Unse lene wala bakaya itna badhega: "),
            Map.entry("This increased what they owe by ", "Unse lene wala bakaya itna badha: "),
            Map.entry("This will increase your payable by ", "Aapka dene wala bakaya itna badhega: "),
            Map.entry("This increased your payable by ", "Aapka dene wala bakaya itna badha: "),
            Map.entry("Stock will be unchanged", "Stock mein koi badlav nahi hoga"),
            Map.entry("Stock was unchanged", "Stock mein koi badlav nahi hua"),
            Map.entry("Not allocated to an invoice", "Kisi bill se adjust nahi kiya gaya"),
            Map.entry("Allocated to document ", "Is bill se adjust: "),
            Map.entry("an excess stays as customer credit", "zyada mile paise customer credit rahenge"),
            Map.entry("payment method not stated", "Cash ya UPI abhi batana hai"),
            Map.entry("amount not stated", "rakam abhi batani hai"),
            Map.entry("Which product? I will not guess the godown.", "Kis maal ki location pooch rahe hain? Naam bataiye."),
            Map.entry("No location is remembered for that product.", "Is maal ki location abhi record mein nahi hai."),
            Map.entry("Ask for the sending time.", "Kis tareekh aur kitne baje yaad dilana hai?"),
            Map.entry("The file could not be downloaded.", "File download nahi hui. Ek baar phir bhejiye."),
            Map.entry("Send the instruction as text.", "Apni baat text mein bhejiye."),
            Map.entry("Send a photo, PDF, or voice note.", "Photo, PDF, ya voice note bhejiye."),
            Map.entry("The voice note could not be converted.", "Voice note padh nahi paaya. Chhota note dobara bhejiye."),
            Map.entry("Say which draft this file belongs to.", "Yeh file kis pending entry ke liye hai?"),
            Map.entry("Which draft should I resume?", "Kaunsi entry wapas kholni hai?"),
            Map.entry("There is no suspended draft to resume.", "Wapas kholne ke liye koi roki hui entry nahi hai."),
            Map.entry("Resumed. ", "Entry wapas khol di. "),
            Map.entry("That amount is already recorded. I will not add it again.", "Yeh rakam pehle se record mein hai. Dobara nahi jodunga."),
            Map.entry("That amount is already recorded; no new debt was added.", "Yeh rakam pehle se record mein hai; naya bakaya nahi joda."),
            Map.entry("That amount is not the balance I have. Say the new amount if it is new.", "Record ka balance alag hai. Naya bakaya hai toh saaf bataiye."),
            Map.entry("I could not find a saved party named ", "Is naam ki party record mein nahi mili: "),
            Map.entry(". Give the saved full name, or explicitly ask me to create this ", ". Record wala poora naam bataiye, ya nayi party jodne ko kahiye: "),
            Map.entry(". Your balance question is still open; no debt was posted.", ". Balance wala sawal yaad hai; koi bakaya nahi joda."),
            Map.entry("More than one party is named ", "Is naam ki ek se zyada party hain: "),
            Map.entry(". Which saved party do you mean?", ". Kaunsi party ki baat hai?"),
            Map.entry("I heard ", "Aapne bataya: "),
            Map.entry(", and no saved party has that name.", ", lekin is naam ki party record mein nahi hai."),
            Map.entry(". The saved party is ", ". Record mein naam hai: "),
            Map.entry(". Say that name if it is the same party.", ". Wahi party hai toh uska poora naam bataiye."),
            Map.entry(", and more than one saved party has that name.", ", aur is naam ki ek se zyada party hain."),
            Map.entry(" has stock. ", " mein stock hai. "), Map.entry(" was last noted. ", " mein aakhri baar record hua tha. "),
            Map.entry("Reminder approved. It stays on the dashboard and is not sent to the customer.", "Reminder confirm ho gaya. Dashboard par dikhega; customer ko message nahi jayega."),
            Map.entry(" is waiting for approval. It is not sent yet.", " ko confirm karna baaki hai. Abhi bheja nahi gaya."),
            Map.entry("a correction", "kya sudharna hai"),
            Map.entry("Which party is this for? I have kept your balance question.", "Kis party ka balance? Aapka sawal yaad rakha hai."),
            Map.entry("Receivable is ₹", "Aapko lene hain ₹"), Map.entry("Payable is ₹", "Aapko dene hain ₹"),
            Map.entry(" (they owe you).", " (unse lene hain)."), Map.entry(" (you owe them).", " (unko dene hain).")
    ).stream().sorted(Comparator.comparingInt((Map.Entry<String, String> entry) -> entry.getKey().length()).reversed()).toList();

    private HinglishReplies() {}

    static String render(String text) {
        if (text == null) return null;
        for (var phrase : PHRASES) text = text.replace(phrase.getKey(), phrase.getValue());
        return text;
    }
}

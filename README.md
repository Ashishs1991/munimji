# Munim Ji

**Your business assistant on WhatsApp.**

Munim Ji helps small business owners record stock, purchases, sales, money owed, and where goods are kept through everyday Hindi, Hinglish, and English conversations. Owners can send voice notes or text, review what the assistant understood, and confirm an entry before it changes their books.

Built for MSMEs in Agra, with a wholesale-shop workflow as the initial use case, Munim Ji aims to make business digitization accessible without requiring owners to learn a new dashboard for every task.

## How it works

- **Record business activity:** turn voice and text into drafts for goods received, sales, payments, and credit entries.
- **Build the catalog conversationally:** clarify unknown parties and products while retaining the quantity, unit, and godown already stated.
- **Remember goods locations:** “Anil bhai ka maal Godown 1 mein rakha hai” creates a location-note proposal without adding purchased stock or changing money owed.
- **Keep the owner in control:** creating a party/product and posting a transaction require separate confirmations. Repeated approvals do not post the same entry twice.
- **Review the business:** use a dashboard for stock, balances, pending work, low-stock alerts, and eligible historical forecasts.
- **Work with supporting records:** attach photos or PDFs for document extraction, import the supported CSV format, and schedule reminders.

### Example conversation

> **Owner:** Gupta se 5 bori plastic boxes liye, Godown 1 mein rakhe hain.
>
> **Munim Ji:** Clarifies the supplier or product if needed, then shows the proposed receipt with its quantity, unit, and godown.
>
> **Owner:** Haan.
>
> **Munim Ji:** Posts the reviewed receipt once. An unknown purchase price does not invent a payable.

An unknown party or product is offered for creation first; a separate confirmation is needed to post stock or money.

## Project status

This is a hackathon MVP with automated tests covering inventory, ledgers, approvals, conversation recovery, reminders, and business isolation. Live model interpretation, speech accuracy, and messaging delivery require rehearsal on the configured deployment.

- Two businesses are isolated from one another; stock and balances belong to the selected business.
- Forecasting is a basic historical baseline and returns insufficient history when records are inadequate.
- Scheduled customer messages need the appropriate WhatsApp setup and verified provider outcomes.
- Tally integration is not included as a verified connector. The optional e-way bill adapter is incomplete and stays disabled.

## Technology

- **Backend:** Java 17 and Spring Boot
- **Database:** PostgreSQL / Supabase
- **AI:** Meta Model API for interpretation and voice transcription
- **Messaging:** WhatsApp Cloud API
- **Dashboard:** Thymeleaf
- **Audio conversion:** FFmpeg

Models propose actions; application code validates and posts approved changes.

## Run locally

1. Install Java 17+, Python 3 for the optional live-check script, and FFmpeg for local voice processing.
2. Copy `.env.example` to `.env` and configure the database, Meta API, WhatsApp credentials, and application settings.
3. Start the application:

   ```sh
   ./mvnw spring-boot:run
   ```

The default address is `http://localhost:8080`; the health check is `/actuator/health`. Database migrations run on startup. Settings are mapped in `src/main/resources/application.yml`.

## Test and rehearse

With Docker running, run the automated suite against disposable PostgreSQL:

```sh
./mvnw test
```

To explicitly run real Meta inference using synthetic conversations and a disposable database:

```sh
python3 scripts/check-live-conversations.py
```

The live check uses your configured Meta key and sends no WhatsApp messages. It is skipped by the default test suite.

## Documentation

- [Product requirements](doc/PRD.md)
- [Technical RFC](doc/RFC-001.md)
- [Conversation checks and pending-draft recovery](doc/CONVERSATION-CHECKS.md)
- [Demo rehearsal checklist](doc/DEMO-CHECKLIST.md)
- [Hackathon rules review](doc/HACKATHON-RULES-REVIEW.md)

## Private data and hackathon contributions

Keep API keys, credentials, customer records, and private business documents out of Git. Store local secrets in `.env` and use synthetic or redacted demo data.

Maintain dated commits and identify work built during the event. The earlier Tally connector is pre-event work; its eligibility needs clarification before reuse. See the hackathon rules review.

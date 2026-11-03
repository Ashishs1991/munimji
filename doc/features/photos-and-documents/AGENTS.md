# Photos and documents

Milestone 14. A photo or PDF stays attached to one open draft. Extraction can fill fields. The owner still approves before stock or money changes.

## What this covers

- A jpeg, png, webp, or PDF is stored with its bytes and a SHA-256. Reading it back returns those same bytes.
- With one open draft, the file attaches to that draft. With two open drafts, the file is refused until the owner names which draft it belongs to.
- A plain-text upload is refused.
- Extraction proposes party, product, quantity, unit, price, and godown on that draft. A request inside the file to post is refused. Stock stays unchanged until the revised draft is approved.
- A photo is sent to the model as an image attachment. A PDF is sent as its extracted text, or as a note that the file has no extractable text. The prompt does not paste a truncated copy of the file.

## Spec

`doc/PRD.md` AC10 and the document half of F02 and F04. `doc/RFC-001.md` section 9: keep the source and do not let model output post. Voice notes and WhatsApp downloads stay open.

## How to check

Docker must be running.

```bash
./mvnw -Dtest=DocumentStoreTest test
```

## Still open

The bytes live in PostgreSQL. The Supabase storage bucket is not used yet. WhatsApp photos now start an independent draft by default; replying to a specific entry can attach evidence to that open draft. See [photo draft isolation](../photo-draft-isolation/AGENTS.md).

# Photo draft isolation

PRD F02/F03 evidence, clarification and versioned approval. V31 adds structured document fields/date, explicit WhatsApp reply references and per-message processing claims. It does not repair or post historical records.

New WhatsApp photos/PDFs receive an independent blank draft before extraction, even when another transaction is pending. Only a validated reply-to reference from the same business, actor and phone can attach a file to an existing open entry; outbound provider message IDs are retained for those references. A failed file does not restore an unrelated old focus.

Document extraction has a fresh context and a strict factual JSON contract. Prose-only output gets one structured retry, then an explicit failure instead of claiming fields were saved. Server-selected target and existing action override model-supplied routing; file content cannot create entities or approve/post. Full product sizes, counts, units, godowns, item lines and printed document date are retained before asking the owner for intent. The printed date is reference metadata; it is displayed separately from the entry effective date, not silently treated as stock-arrival time.

Kharida ha/hai and Hindi equivalents continue the photo's STOCK_ACTION question directly using its stored facts. No old-party/product fields are borrowed from history. Extraction traces retain the raw response and the resulting draft.

Inbox claims stop two updated local/deployed workers processing the same inbound event simultaneously. Claims expire after ten minutes; processing beyond that requires renewal (documented shortcut). All workers must run the new version to honor claims. This does not imply WhatsApp provider acceptance is delivery.

Run ./mvnw test with Docker. Synthetic tests cover PP Traders vs an older receipt, prose-to-JSON repair, product size/date preservation, document target injection, explicit reply attachment, and competing worker claims. Real vision/model calls remain for user-run WhatsApp rehearsal. No real photo or business data is committed as a fixture.

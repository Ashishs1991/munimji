# Catalog and parties

Milestone 3. Products stay distinct, pack conversions are approved before use, and parties with the same name are not merged.

## What this covers

- A product keeps its own SKU, name, brand, and variant. Similar names return a candidate list and are not combined.
- An alias resolves only after it is approved for that business. The same alias in another business does not match.
- The base unit is stored with the product. Extra units, such as one box = 10 pieces, are stored only when approved. An approved factor is not overwritten.
- Piece quantities that are not whole numbers are rejected. A divisible unit such as kilograms may be fractional.
- Tax status stays `UNCONFIGURED`. No rate is guessed.
- A party is customer, supplier, or both only when that role is set. Creating another party with the same display name creates a new id.

## Spec

`doc/PRD.md` AC01. `doc/RFC-001.md` product identity, pack conversion, and the parties table in sections 6 and 7.

## How to check

Docker must be running.

```bash
./mvnw -Dtest=CatalogAndPartyTest test
```

## Still open

Opening counts, stock balances, and money are the next milestones. Supplier-scoped aliases are not used yet.
